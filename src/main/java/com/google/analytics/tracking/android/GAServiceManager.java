package com.google.analytics.tracking.android;

import android.content.Context;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Handler.Callback;
import android.os.Message;
import com.google.analytics.tracking.android.GAUsage.Field;
import com.google.android.gms.common.util.VisibleForTesting;

public class GAServiceManager implements ServiceManager {
    private static final int MSG_KEY = 1;
    /* access modifiers changed from: private|static|final */
    public static final Object MSG_OBJECT = new Object();
    private static GAServiceManager instance;
    /* access modifiers changed from: private */
    public boolean connected = true;
    private Context ctx;
    /* access modifiers changed from: private */
    public int dispatchPeriodInSeconds = 1800;
    /* access modifiers changed from: private */
    public Handler handler;
    private boolean listenForNetwork = true;
    private AnalyticsStoreStateListener listener = new AnalyticsStoreStateListener() {
        public void reportStoreIsEmpty(boolean isEmpty) {
            GAServiceManager.this.updatePowerSaveMode(isEmpty, GAServiceManager.this.connected);
        }
    };
    private GANetworkReceiver networkReceiver;
    private boolean pendingDispatch = true;
    private AnalyticsStore store;
    /* access modifiers changed from: private */
    public boolean storeIsEmpty = false;
    private volatile AnalyticsThread thread;

    public static GAServiceManager getInstance() {
        if (instance == null) {
            instance = new GAServiceManager();
        }
        return instance;
    }

    private GAServiceManager() {
    }

    @VisibleForTesting
    GAServiceManager(Context ctx, AnalyticsThread thread, AnalyticsStore store, boolean listenForNetwork) {
        this.store = store;
        this.thread = thread;
        this.listenForNetwork = listenForNetwork;
        initialize(ctx, thread);
    }

    private void initializeNetworkReceiver() {
        this.networkReceiver = new GANetworkReceiver(this);
        IntentFilter filter = new IntentFilter();
        filter.addAction("android.net.conn.CONNECTIVITY_CHANGE");
        this.ctx.registerReceiver(this.networkReceiver, filter);
    }

    private void initializeHandler() {
        this.handler = new Handler(this.ctx.getMainLooper(), new Callback() {
            public boolean handleMessage(Message msg) {
                if (1 == msg.what && GAServiceManager.MSG_OBJECT.equals(msg.obj)) {
                    GAUsage.getInstance().setDisableUsage(true);
                    GAServiceManager.this.dispatch();
                    GAUsage.getInstance().setDisableUsage(false);
                    if (GAServiceManager.this.dispatchPeriodInSeconds > 0 && !GAServiceManager.this.storeIsEmpty) {
                        GAServiceManager.this.handler.sendMessageDelayed(GAServiceManager.this.handler.obtainMessage(1, GAServiceManager.MSG_OBJECT), (long) (GAServiceManager.this.dispatchPeriodInSeconds * 1000));
                    }
                }
                return true;
            }
        });
        if (this.dispatchPeriodInSeconds > 0) {
            this.handler.sendMessageDelayed(this.handler.obtainMessage(1, MSG_OBJECT), (long) (this.dispatchPeriodInSeconds * 1000));
        }
    }

    /* access modifiers changed from: declared_synchronized */
    public synchronized void initialize(Context ctx, AnalyticsThread thread) {
        if (this.ctx == null) {
            this.ctx = ctx.getApplicationContext();
            if (this.thread == null) {
                this.thread = thread;
                if (this.pendingDispatch) {
                    thread.dispatch();
                }
            }
        }
    }

    /* access modifiers changed from: 0000 */
    @VisibleForTesting
    public AnalyticsStoreStateListener getListener() {
        return this.listener;
    }

    /* access modifiers changed from: declared_synchronized */
    public synchronized AnalyticsStore getStore() {
        if (this.store == null) {
            if (this.ctx == null) {
                throw new IllegalStateException("Cant get a store unless we have a context");
            }
            this.store = new PersistentAnalyticsStore(this.listener, this.ctx);
        }
        if (this.handler == null) {
            initializeHandler();
        }
        if (this.networkReceiver == null && this.listenForNetwork) {
            initializeNetworkReceiver();
        }
        return this.store;
    }

    public synchronized void dispatch() {
        if (this.thread == null) {
            Log.w("dispatch call queued.  Need to call GAServiceManager.getInstance().initialize().");
            this.pendingDispatch = true;
        } else {
            GAUsage.getInstance().setUsage(Field.DISPATCH);
            this.thread.dispatch();
        }
    }

    public synchronized void setDispatchPeriod(int dispatchPeriodInSeconds) {
        if (this.handler == null) {
            Log.w("Need to call initialize() and be in fallback mode to start dispatch.");
            this.dispatchPeriodInSeconds = dispatchPeriodInSeconds;
        } else {
            GAUsage.getInstance().setUsage(Field.SET_DISPATCH_PERIOD);
            if (!this.storeIsEmpty && this.connected && this.dispatchPeriodInSeconds > 0) {
                this.handler.removeMessages(1, MSG_OBJECT);
            }
            this.dispatchPeriodInSeconds = dispatchPeriodInSeconds;
            if (dispatchPeriodInSeconds > 0 && !this.storeIsEmpty && this.connected) {
                this.handler.sendMessageDelayed(this.handler.obtainMessage(1, MSG_OBJECT), (long) (dispatchPeriodInSeconds * 1000));
            }
        }
    }

    /* access modifiers changed from: declared_synchronized */
    @VisibleForTesting
    public synchronized void updatePowerSaveMode(boolean storeIsEmpty, boolean connected) {
        if (!(this.storeIsEmpty == storeIsEmpty && this.connected == connected)) {
            if (storeIsEmpty || !connected) {
                if (this.dispatchPeriodInSeconds > 0) {
                    this.handler.removeMessages(1, MSG_OBJECT);
                }
            }
            if (!storeIsEmpty && connected && this.dispatchPeriodInSeconds > 0) {
                this.handler.sendMessageDelayed(this.handler.obtainMessage(1, MSG_OBJECT), (long) (this.dispatchPeriodInSeconds * 1000));
            }
            StringBuilder append = new StringBuilder().append("PowerSaveMode ");
            String str = (storeIsEmpty || !connected) ? "initiated." : "terminated.";
            Log.iDebug(append.append(str).toString());
            this.storeIsEmpty = storeIsEmpty;
            this.connected = connected;
        }
    }

    public synchronized void updateConnectivityStatus(boolean connected) {
        updatePowerSaveMode(this.storeIsEmpty, connected);
    }
}
