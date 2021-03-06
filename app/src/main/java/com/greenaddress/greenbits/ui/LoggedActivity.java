package com.greenaddress.greenbits.ui;

import android.content.Intent;
import android.util.Log;
import android.view.MotionEvent;
import android.widget.EditText;
import android.widget.Toast;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.greenaddress.greenapi.ConnectionManager;
import com.greenaddress.greenapi.model.Model;
import com.greenaddress.greenapi.model.ToastObservable;
import com.greenaddress.greenbits.ui.preferences.PrefKeys;

import java.text.NumberFormat;
import java.text.ParseException;
import java.util.Locale;
import java.util.Observable;
import java.util.Observer;
import java.util.Timer;
import java.util.TimerTask;

public abstract class LoggedActivity extends GaActivity implements Observer {

    private boolean mChangingActivity = false;
    private Timer mTimer = new Timer();
    private long mStart = System.currentTimeMillis();

    @Override
    public void onResume() {
        super.onResume();

        final boolean timerExpired = mStart + delayLogoutTimer() < System.currentTimeMillis();
        if (timerExpired || modelIsNullOrDisconnected()) {
            exit();
            return;
        }

        getConnectionManager().addObserver(this);
        getModel().getToastObservable().addObserver(this);
        startLogoutTimer();
    }

    @Override
    public void onPause() {
        super.onPause();
        if (modelIsNullOrDisconnected()) {
            exit();
            return;
        }

        stopLogoutTimer();
        mStart = System.currentTimeMillis();
        getConnectionManager().deleteObserver(this);
        getModel().getToastObservable().deleteObserver(this);
    }

    @Override
    public void update(final Observable observable, final Object o) {
        if (observable instanceof ConnectionManager) {
            final ConnectionManager cm = getConnectionManager();
            if (cm.isLoginRequired() || cm.isDisconnected()) {
                exit();
            }
        } else if (observable instanceof ToastObservable) {
            final ToastObservable tObs = (ToastObservable) observable;
            UI.toast(this, tObs.getMessage(getResources()), Toast.LENGTH_LONG);
        }
    }

    public void logout() {
        getGAApp().getExecutor().execute(() -> {
            startLoading();
            getConnectionManager().disconnect();
            stopLoading();
            runOnUiThread(() -> exit());
        });
    }

    private void exit() {
        if (!mChangingActivity) {
            mChangingActivity = true;
            final Intent intent = GaActivity.createToFirstIntent(this);
            startActivity(intent);
            finishAffinity();
        }
    }

    @Override
    public boolean dispatchTouchEvent(final MotionEvent ev) {
        stopLogoutTimer();
        startLogoutTimer();
        return super.dispatchTouchEvent(ev);
    }

    private int delayLogoutTimer() {
        if (getModel() != null && getModel().getSettings() != null) {
            return getModel().getSettings().getAltimeout()  * 60 * 1000;
        }
        final String altimeString = cfg().getString(PrefKeys.ALTIMEOUT, "5");
        return Integer.parseInt(altimeString) * 60 * 1000;
    }

    private void startLogoutTimer() {
        stopLogoutTimer();
        final Timer timer = new Timer();
        timer.schedule(new TimerTask() {
            @Override
            public void run() {
                exit();
            }
        }, delayLogoutTimer());
        mTimer = timer;
    }

    private void stopLogoutTimer() {
        if (mTimer != null) {
            mTimer.cancel();
            mTimer.purge();
        }
    }

    public boolean modelIsNullOrDisconnected() {
        return getModel() == null || getConnectionManager() == null || getConnectionManager().isDisconnected();
    }

    protected String getBitcoinUnitClean() {
        return getModel().getUnitKey();
    }

    protected void setAmountText(final EditText amountText, final boolean isFiat, final ObjectNode currentAmount) {
        try {
            final NumberFormat us = Model.getNumberFormat(8, Locale.US);
            final NumberFormat fiatNf = Model.getNumberFormat(2);
            final NumberFormat btcNf = getModel().getNumberFormat();
            final String fiat = fiatNf.format( us.parse(currentAmount.get("fiat").asText()) );
            final String btc = btcNf.format( us.parse(currentAmount.get(getBitcoinUnitClean()).asText()));
            amountText.setText(isFiat ? fiat : btc);
        } catch (ParseException e) {
            Log.e(TAG,e.getMessage());
        }
    }

    protected void removeUtxosIfTooBig(final ObjectNode transactionFromUri) {
        if (transactionFromUri.toString().length() <= 200000)
            return;
        if (transactionFromUri.has("utxos")) {
            transactionFromUri.remove("utxos");
        }
        if (transactionFromUri.get("send_all").asBoolean() && transactionFromUri.has("used_utxos")) {
            transactionFromUri.remove("used_utxos");
        }
    }
}
