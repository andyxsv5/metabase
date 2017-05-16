(ns metabase.sync-database.classify
  (:require [cheshire.core :as json]
            [clojure.math.numeric-tower :as math]
            [clojure.string :as s]
            [clojure.tools.logging :as log]
            [metabase
             [driver :as driver]
             [util :as u]]
            [metabase.db.metadata-queries :as queries]
            [metabase.models
             [field :as field]
             [field-values :as field-values]
             [table :as table]]
            [metabase.sync-database.interface :as i]
            [schema.core :as schema]
            [toucan.db :as db]))

(def ^:private ^:const ^Float percent-valid-url-threshold
  "Fields that have at least this percent of values that are valid URLs should be given a special type of `:type/URL`."
  0.95)

(def ^:const ^Integer low-cardinality-threshold
  "Fields with less than this many distinct values should automatically be given a special type of `:type/Category`."
  300)

(def ^:private ^:const ^Integer average-length-no-preview-threshold
  "Fields whose values' average length is greater than this amount should be marked as `preview_display = false`."
  50)
;; save point: trying to remove driver references from here.
(defn test-for-cardinality?
  "Should FIELD should be tested for cardinality?"
  [field #_is-new?]
  (or (field-values/field-should-have-field-values? field)
      (and (nil? (:special_type field))
           #_is-new?
           (not (isa? (:base_type field) :type/DateTime))
           (not (isa? (:base_type field) :type/Collection))
           (not (= (:base_type field) :type/*)))))

(defn- test:category-special-type
  "fields wtih less than low-cardinality-threshold default to :type/Category"
  ;; this used to only apply to new fields and that was removed in refactor, does that break things
  [field field-stats]
  (cond-> field-stats
    (and (test-for-cardinality? field)
         (nil? (:special_type field))
         (pos? (count (:values field-stats)))) (assoc :special-type :type/Category)))

(defn- test:no-preview-display
  "If FIELD's is textual and its average length is too great, mark it so it isn't displayed in the UI."
  [driver field field-stats]
  (if-not (and (= :normal (:visibility_type field))
               (isa? (:base_type field) :type/Text))
    ;; this field isn't suited for this test
    field-stats
    ;; test for avg length
    (let [avg-len (u/try-apply (:field-avg-length driver) field)]
      (if-not (and avg-len (> avg-len average-length-no-preview-threshold))
        field-stats
        (do
          (log/debug (u/format-color 'green "Field '%s' has an average length of %d. Not displaying it in previews." (field/qualified-name field) avg-len))
          (assoc field-stats :preview-display false))))))

(defn- test:url-special-type
  "If FIELD is texual, doesn't have a `special_type`, and its non-nil values are primarily URLs, mark it as `special_type` `:type/URL`."
  [driver field field-stats]
  (if-not (and (not (:special_type field))
               (isa? (:base_type field) :type/Text))
    ;; this field isn't suited for this test
    field-stats
    ;; test for url values
    (let [percent-urls (u/try-apply (:field-percent-urls driver) field)]
      (if-not (and (float? percent-urls)
                   (>= percent-urls 0.0)
                   (<= percent-urls 100.0)
                   (> percent-urls percent-valid-url-threshold))
        field-stats
        (do
          (log/debug (u/format-color 'green "Field '%s' is %d%% URLs. Marking it as a URL." (field/qualified-name field) (int (math/round (* 100 percent-urls)))))
          (assoc field-stats :special-type :url))))))

(defn- values-are-valid-json?
  "`true` if at every item in VALUES is `nil` or a valid string-encoded JSON dictionary or array, and at least one of those is non-nil."
  [values]
  (try
    (loop [at-least-one-non-nil-value? false, [val & more] values]
      (cond
        (and (not val)
             (not (seq more))) at-least-one-non-nil-value?
        (s/blank? val)         (recur at-least-one-non-nil-value? more)
        ;; If val is non-nil, check that it's a JSON dictionary or array. We don't want to mark Fields containing other
        ;; types of valid JSON values as :json (e.g. a string representation of a number or boolean)
        :else                  (do (u/prog1 (json/parse-string val)
                                     (assert (or (map? <>)
                                                 (sequential? <>))))
                                   (recur true more))))
    (catch Throwable _
      false)))

(defn- test:json-special-type
  "Mark FIELD as `:json` if it's textual, doesn't already have a special type, the majority of it's values are non-nil, and all of its non-nil values
   are valid serialized JSON dictionaries or arrays."
  [driver field field-stats]
  (if (or (:special_type field)
          (not (isa? (:base_type field) :type/Text)))
    ;; this field isn't suited for this test
    field-stats
    ;; check for json values
    (if-not (values-are-valid-json? (take driver/max-sync-lazy-seq-results (driver/field-values-lazy-seq driver field)))
      field-stats
      (do
        (log/debug (u/format-color 'green "Field '%s' looks like it contains valid JSON objects. Setting special_type to :type/SerializedJSON." (field/qualified-name field)))
        (assoc field-stats :special-type :type/SerializedJSON, :preview-display false)))))

(defn- values-are-valid-emails?
  "`true` if at every item in VALUES is `nil` or a valid email, and at least one of those is non-nil."
  [values]
  (try
    (loop [at-least-one-non-nil-value? false, [val & more] values]
      (cond
        (and (not val)
             (not (seq more))) at-least-one-non-nil-value?
        (s/blank? val)         (recur at-least-one-non-nil-value? more)
        ;; If val is non-nil, check that it's a JSON dictionary or array. We don't want to mark Fields containing other
        ;; types of valid JSON values as :json (e.g. a string representation of a number or boolean)
        :else                  (do (assert (u/is-email? val))
                                   (recur true more))))
    (catch Throwable _
      false)))

(defn- test:email-special-type
  "Mark FIELD as `:email` if it's textual, doesn't already have a special type, the majority of it's values are non-nil, and all of its non-nil values
   are valid emails."
  [driver field field-stats]
  (if (or (:special_type field)
          (not (isa? (:base_type field) :type/Text)))
    ;; this field isn't suited for this test
    field-stats
    ;; check for emails
    (if-not (values-are-valid-emails? (take driver/max-sync-lazy-seq-results (driver/field-values-lazy-seq driver field)))
      field-stats
      (do
        (log/debug (u/format-color 'green "Field '%s' looks like it contains valid email addresses. Setting special_type to :type/Email." (field/qualified-name field)))
        (assoc field-stats :special-type :type/Email, :preview-display true)))))

(defn- test:new-field
  "Do the various tests that should only be done for a new `Field`.
   We only run most of the field analysis work when the field is NEW in order to favor performance of the sync process."
  [driver field field-stats]
  (->> field-stats
       (test:category-special-type driver field)
       (test:no-preview-display driver field)
       (test:url-special-type   driver field)
       (test:json-special-type  driver field)
       (test:email-special-type driver field)))

(defn classify-table [driver table]
  (for [{:keys [id] :as field} (table/fields table)]
    (let [new-field? #_FIXME true #_(contains? new-field-ids id)]
      (cond->> {:id id}
        #_(test-for-cardinality? field new-field?) #_(test:cardinality-and-extract-field-values field)
        new-field?                               (test:new-field driver field)))))
