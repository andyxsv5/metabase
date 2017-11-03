import React from "react";
import _ from "underscore"
import { handleActions } from "redux-actions";
import { combineReducers } from "redux";
import { addUndo, createUndo } from "metabase/redux/undo";

import { AlertApi } from "metabase/services";
import { RestfulRequest } from "metabase/lib/request";
import { getUser } from "metabase/selectors/user";
import { deletePulse } from "metabase/pulse/actions";
import Icon from "metabase/components/Icon.jsx";

export const FETCH_ALL_ALERTS = 'metabase/alerts/FETCH_ALL_ALERTS'
const fetchAllAlertsRequest = new RestfulRequest({
    endpoint: AlertApi.table_xray,
    actionPrefix: FETCH_ALL_ALERTS,
    storeAsDictionary: true
})
export const fetchAllAlerts = () => {
    return async (dispatch, getState) => {
        await dispatch(fetchAllAlertsRequest.trigger())
        dispatch.action(FETCH_ALL_ALERTS)
    }
}

export const FETCH_ALERTS_FOR_QUESTION = 'metabase/alerts/FETCH_ALERTS_FOR_QUESTION'
const fetchAlertsForQuestionRequest = new RestfulRequest({
    endpoint: AlertApi.list_for_question,
    actionPrefix: FETCH_ALERTS_FOR_QUESTION,
    storeAsDictionary: true
})
export const fetchAlertsForQuestion = (questionId) => {
    return async (dispatch, getState) => {
        await dispatch(fetchAlertsForQuestionRequest.trigger({ questionId }))
        dispatch.action(FETCH_ALERTS_FOR_QUESTION)
    }
}

export const CREATE_ALERT = 'metabase/alerts/CREATE_ALERT'
const createAlertRequest = new RestfulRequest({
    endpoint: AlertApi.create,
    actionPrefix: CREATE_ALERT,
    storeAsDictionary: true
})
export const createAlert = (alert) => {
    return async (dispatch, getState) => {
        // TODO: How to handle a failed creation and display it to a user?
        // Maybe RestfulRequest.trigger should throw an exception
        // that the React component calling createAlert could catch ...?
        await dispatch(createAlertRequest.trigger(alert))

        dispatch(addUndo(createUndo({
            type: "create-alert",
            // eslint-disable-next-line react/display-name
            message: () => <div className="flex align-center text-bold"><Icon name="alertConfirm" size="19" className="mr2 text-success" />Your alert is all set up.</div>,
            action: null // alert creation is not undoable
        })));

        dispatch.action(CREATE_ALERT)
    }
}

export const UPDATE_ALERT = 'metabase/alerts/UPDATE_ALERT'
const updateAlertRequest = new RestfulRequest({
    endpoint: AlertApi.update,
    actionPrefix: UPDATE_ALERT,
    storeAsDictionary: true
})
export const updateAlert = (alert) => {
    return async (dispatch, getState) => {
        await dispatch(updateAlertRequest.trigger(alert))
        dispatch.action(UPDATE_ALERT)
    }
}

export const UNSUBSCRIBE_FROM_ALERT = 'metabase/alerts/UNSUBSCRIBE_FROM_ALERT'
export const unsubscribeFromAlert = (alert) => {
    return async (dispatch, getState) => {
        const user = getUser(getState())
        await dispatch(updateAlert({
            ...alert,
            channels: alert.channels.map(c => c.channel_type !== "email" ? c :
                { ...c, recipients: c.recipients.filter(r => r.id !== user.id)}
            )
        }));
        dispatch.action(UNSUBSCRIBE_FROM_ALERT)
    }
}

// TODO: the D of CRUD isn't yet supported by RestfulRequest – that could deserve some love
export const DELETE_ALERT = 'metabase/alerts/DELETE_ALERT'
export const deleteAlert = (alertId) => {
    return async (dispatch, getState) => {
        await dispatch(deletePulse(alertId));
        dispatch.action(DELETE_ALERT, alertId)
    }
}

const alerts = handleActions({
    ...fetchAllAlertsRequest.getReducers(),
    ...fetchAlertsForQuestionRequest.getReducers(),
    ...createAlertRequest.getReducers(),
    ...updateAlertRequest.getReducers(),
    // removal from the result dictionary
    [DELETE_ALERT]: (state, { payload: alertId }) => ({
        ...state,
        result: _.omit(state.result || {}, alertId)
    })
}, []);

export default combineReducers({
    alerts
});