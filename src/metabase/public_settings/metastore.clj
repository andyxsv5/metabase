(ns metabase.public-settings.metastore
  "Settings related to checking token validity and accessing the MetaStore."
  (:require [cheshire.core :as json]
            [clojure.core.memoize :as memoize]
            [clojure.tools.logging :as log]
            [clj-http.client :as client]
            [environ.core :refer [env]]
            [metabase.models.setting :as setting :refer [defsetting]]
            [metabase.config :as config]
            [metabase.util :as u]
            [metabase.util.schema :as su]
            [schema.core :as s]))

(def ^:private ValidToken
  "Schema for a valid metastore token. Must be 64 lower-case hex characters."
  #"^[0-9a-f]{64}$")

(def store-url
  "URL to the MetaStore. Hardcoded by default but for development purposes you can use a local server. Specify the env
   var `METASTORE_DEV_SERVER_URL`."
  (or
   ;; only enable the changing the store url during dev because we don't want people switching it out in production!
   (when config/is-dev?
     (env :metastore-dev-server-url))
   "https://metastore-demo.herokuapp.com"))

;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                                TOKEN VALIDATION                                                |
;;; +----------------------------------------------------------------------------------------------------------------+

(defn- token-status-url [token]
  (when (seq token)
    (format "%s/api/%s/status" store-url token)))

(def ^:private ^:const fetch-token-status-timeout-ms 10000) ; 10 seconds

(s/defn ^:private ^:always-validate fetch-token-status :- {:valid s/Bool, :status su/NonBlankString}
  "Fetch info about the validity of TOKEN from the MetaStore. "
  [token :- ValidToken]
  (try
    ;; attempt to query the metastore API about the status of this token. If the request doesn't complete in a
    ;; reasonable amount of time throw a timeout exception
    (deref (future
             (try (some-> (token-status-url token)
                          slurp
                          (json/parse-string keyword))
                  ;; slurp will throw a FileNotFoundException for 404s, so in that case just return an appropriate
                  ;; 'Not Found' message
                  (catch java.io.FileNotFoundException e
                    {:valid false, :status "invalid token: not found."})
                  ;; if there was any other error fetching the token, log it and return a generic message about the
                  ;; token being invalid. This message will get displayed in the Settings page in the admin panel so
                  ;; we do not want something complicated
                  (catch Throwable e
                    (log/error "Error fetching token status:" e)
                    {:valid false, :status "there was an error checking whether this token was valid."})))
           fetch-token-status-timeout-ms
           {:valid false, :status "token validation timed out."})))

(defn- check-embedding-token-is-valid* [token]
  (when (s/check ValidToken token)
    (throw (Exception. "Invalid token: token isn't in the right format.")))
  (log/info "Checking with the MetaStore to see whether" token "is valid...")
  (let [{:keys [valid status]} (fetch-token-status token)]
    (or valid
        ;; if token isn't valid throw an Exception with the `:status` message
        (throw (Exception. ^String status)))))

(def ^:private ^:const valid-token-recheck-interval-ms
  "Amount of time to cache the status of a valid embedding token before forcing a re-check"
  (* 1000 60 60 24)) ; once a day

(def ^:private ^{:arglists '([token])} check-embedding-token-is-valid
  "Check whether TOKEN is valid. Throws an Exception if not."
  ;; this is just `check-embedding-token-is-valid*` with some light caching
  (memoize/ttl check-embedding-token-is-valid*
    :ttl/threshold valid-token-recheck-interval-ms))


;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                             SETTING & RELATED FNS                                              |
;;; +----------------------------------------------------------------------------------------------------------------+

;; TODO - better docstring
(defsetting premium-embedding-token
  "Token for premium embedding. Go to the MetaStore to get yours!"
  :setter (fn [new-value]
            ;; validate the new value if we're not unsetting it
            (when (seq new-value)
              (check-embedding-token-is-valid new-value)
              (log/info "Token is valid."))
            (setting/set-string! :premium-embedding-token new-value)))

(defn hide-embed-branding?
  "Should we hide the 'Powered by Metabase' attribution on the embedding pages? `true` if we have a valid premium
   embedding token."
  []
  (boolean
   (u/ignore-exceptions
     (check-embedding-token-is-valid (premium-embedding-token)))))
