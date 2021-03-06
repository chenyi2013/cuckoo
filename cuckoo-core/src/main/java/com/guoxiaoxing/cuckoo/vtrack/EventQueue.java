package com.guoxiaoxing.cuckoo.vtrack;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.text.TextUtils;
import android.widget.Toast;

import com.guoxiaoxing.cuckoo.Cuckoo;
import com.guoxiaoxing.cuckoo.db.DbAdapter;
import com.guoxiaoxing.cuckoo.exception.ConnectErrorException;
import com.guoxiaoxing.cuckoo.exception.DebugModeException;
import com.guoxiaoxing.cuckoo.exception.InvalidDataException;
import com.guoxiaoxing.cuckoo.exception.ResponseErrorException;
import com.guoxiaoxing.cuckoo.util.Base64Coder;
import com.guoxiaoxing.cuckoo.util.DataUtils;
import com.guoxiaoxing.cuckoo.util.JSONUtils;
import com.guoxiaoxing.cuckoo.util.LogUtils;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

/**
 * Manage communication of events with the internal database and the SensorsData servers.
 * <p/>
 * <p>This class straddles the thread boundary between user threads and
 * a logical SensorsData thread.
 */
public class EventQueue {

    private static final String TAG = "SCuckoo.EventQueue";

    // Used across thread boundaries
    private final Worker mWorker;
    private final Context mContext;
    private final DbAdapter mDbAdapter;

    // Messages for our thread
    private static final int FLUSH_QUEUE = 3;
    private static final int CHECK_CONFIGURE = 4; // 从SA获取配置信息

    private static final Map<Context, EventQueue> sInstances =
            new HashMap<Context, EventQueue>();

    /**
     * Do not call directly. You should call EventQueue.getInstance()
     */
    public EventQueue(final Context context, final String packageName) {
        mContext = context;
        mDbAdapter = new DbAdapter(mContext, packageName/*dbName*/);
        mWorker = new Worker();
    }

    /**
     * Use this to get an instance of EventQueue instead of creating one directly
     * for yourself.
     *
     * @param messageContext should be the Main Activity of the application
     *                       associated with these messages.
     */
    public static EventQueue getInstance(final Context messageContext, final String
            packageName) {
        synchronized (sInstances) {
            final Context appContext = messageContext.getApplicationContext();
            final EventQueue ret;
            if (!sInstances.containsKey(appContext)) {
                ret = new EventQueue(appContext, packageName);
                sInstances.put(appContext, ret);
            } else {
                ret = sInstances.get(appContext);
            }
            return ret;
        }
    }

    public void enqueueEvent(final String type, final JSONObject eventJson) {
        try {
            synchronized (mDbAdapter) {
                int ret = mDbAdapter.addJSON(eventJson, DbAdapter.Table.EVENTS);
                if (ret < 0) {
                    String error = "Failed to enqueue the event: " + eventJson;
                    if (Cuckoo.with(mContext).isDebugMode()) {
                        throw new DebugModeException(error);
                    } else {
                        LogUtils.i(TAG, error);
                    }
                }

                final Message m = Message.obtain();
                m.what = FLUSH_QUEUE;

                if (Cuckoo.with(mContext).isDebugMode() || ret ==
                        DbAdapter.DB_OUT_OF_MEMORY_ERROR) {
                    mWorker.sendMessage(m);
                } else {
                    // track_signup 立即发送
                    if (type.equals("track_signup") || ret > Cuckoo.with(mContext)
                            .getFlushBulkSize()) {
                        mWorker.sendMessage(m);
                    } else {
                        final int interval = Cuckoo.with(mContext).getFlushInterval();
                        mWorker.sendMessageOnce(m, interval);
                    }
                }
            }
        } catch (Exception e) {
            LogUtils.i(TAG, "enqueueEvent error:" + e);
        }
    }

    public void checkConfigure(final DecideMessages check) {
        final Message m = Message.obtain();
        m.what = CHECK_CONFIGURE;
        m.obj = check;

        mWorker.sendMessage(m);
    }

    public void flush() {
        final Message m = Message.obtain();
        m.what = FLUSH_QUEUE;

        mWorker.sendMessage(m);
    }

    public static byte[] slurp(final InputStream inputStream)
            throws IOException {
        final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

        int nRead;
        byte[] data = new byte[8192];

        while ((nRead = inputStream.read(data, 0, data.length)) != -1) {
            buffer.write(data, 0, nRead);
        }

        buffer.flush();
        return buffer.toByteArray();
    }

    public void sendData() {
        try {
            if (TextUtils.isEmpty(Cuckoo.with(mContext).getServerUrl())) {
                return;
            }
            //不是主进程
            if (!DataUtils.isMainProcess(mContext, Cuckoo.with(mContext).getMainProcessName())) {
                return;
            }

            //无网络
            if (!DataUtils.isNetworkAvailable(mContext)) {
                return;
            }

            //不符合同步数据的网络策略
            String networkType = DataUtils.networkType(mContext);
            if (!Cuckoo.with(mContext).isShouldFlush(networkType)) {
                return;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        int count = 100;
        Toast toast = null;
        while (count > 0) {
            boolean deleteEvents = true;
            InputStream in = null;
            OutputStream out = null;
            BufferedOutputStream bout = null;
            HttpURLConnection connection = null;
            String[] eventsData;
            synchronized (mDbAdapter) {
                if (Cuckoo.with(mContext).isDebugMode()) {
                    eventsData = mDbAdapter.generateDataString(DbAdapter.Table.EVENTS, 1);
                } else {
                    eventsData = mDbAdapter.generateDataString(DbAdapter.Table.EVENTS, 50);
                }
            }
            if (eventsData == null) {
                return;
            }

            final String lastId = eventsData[0];
            final String rawMessage = eventsData[1];
            String errorMessage = null;

            try {

                String data;
                try {
                    data = encodeData(rawMessage);
                } catch (IOException e) {
                    // 格式错误，直接将数据删除
                    throw new InvalidDataException(e);
                }

                try {
                    final URL url = new URL(Cuckoo.with(mContext).getServerUrl());
                    connection = (HttpURLConnection) url.openConnection();
                    try {
                        String ua = DataUtils.getUserAgent(mContext);
                        if (TextUtils.isEmpty(ua)) {
                            ua = "SensorsAnalytics Android SDK";
                        }
                        connection.addRequestProperty("User-Agent", ua);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                    if (Cuckoo.with(mContext).isDebugMode() && !Cuckoo.with
                            (mContext).isDebugWriteData()) {
                        connection.addRequestProperty("Dry-Run", "true");
                    }
                    Uri.Builder builder = new Uri.Builder();
                    builder.appendQueryParameter("data_list", data);
                    builder.appendQueryParameter("gzip", "1");
                    if (!TextUtils.isEmpty(data)) {
                        builder.appendQueryParameter("crc", String.valueOf(data.hashCode()));
                    }

                    String query = builder.build().getEncodedQuery();

                    connection.setFixedLengthStreamingMode(query.getBytes().length);
                    connection.setDoOutput(true);
                    connection.setRequestMethod("POST");
                    out = connection.getOutputStream();
                    bout = new BufferedOutputStream(out);
                    bout.write(query.getBytes("UTF-8"));
                    bout.flush();
                    bout.close();
                    bout = null;
                    out.close();
                    out = null;

                    int responseCode = connection.getResponseCode();
                    try {
                        in = connection.getInputStream();
                    } catch (FileNotFoundException e) {
                        in = connection.getErrorStream();
                    }
                    byte[] responseBody = slurp(in);
                    in.close();
                    in = null;

                    String response = new String(responseBody, "UTF-8");

                    //if (Cuckoo.with(mContext).isDebugMode()) {
                    if (responseCode == 200) {
                        LogUtils.i(TAG, String.format("valid message: \n%s", JSONUtils.formatJson(rawMessage)));
                    } else {
                        LogUtils.i(TAG, String.format("invalid message: \n%s", JSONUtils.formatJson(rawMessage)));
                        LogUtils.i(TAG, String.format(Locale.CHINA, "ret_code: %d", responseCode));
                        LogUtils.i(TAG, String.format(Locale.CHINA, "ret_content: %s", response));
                    }
                    //}

                    if (responseCode < 200 || responseCode >= 300) {
                        // 校验错误，直接将数据删除
                        throw new ResponseErrorException(String.format("flush failure with response '%s'",
                                response));
                    }
                } catch (IOException e) {
                    throw new ConnectErrorException(e);
                }
            } catch (ConnectErrorException e) {
                deleteEvents = false;
                errorMessage = "Connection error: " + e.getMessage();
            } catch (InvalidDataException e) {
                deleteEvents = true;
                errorMessage = "Invalid data: " + e.getMessage();
            } catch (ResponseErrorException e) {
                deleteEvents = true;
                errorMessage = "ResponseErrorException: " + e.getMessage();
            } catch (Exception e) {
                deleteEvents = false;
                errorMessage = "Exception: " + e.getMessage();
            } finally {
                boolean isDebugMode = Cuckoo.with(mContext).isDebugMode();
                if (!TextUtils.isEmpty(errorMessage)) {
                    if (isDebugMode || Cuckoo.ENABLE_LOG) {
                        LogUtils.i(TAG, errorMessage);
                        if (isDebugMode && Cuckoo.SHOW_DEBUG_INFO_VIEW) {
                            try {
                                if (toast != null) {
                                    toast.cancel();
                                }
                                toast = Toast.makeText(mContext, errorMessage, Toast.LENGTH_SHORT);
                                toast.show();
                            } catch (Exception e) {
                                e.printStackTrace();
                            }
                        }
                    }
                }

                if (deleteEvents) {
                    count = mDbAdapter.cleanupEvents(lastId, DbAdapter.Table.EVENTS);
                    LogUtils.i(TAG, String.format(Locale.CHINA, "Events flushed. [left = %d]", count));
                } else {
                    count = 0;
                }
                if (null != bout)
                    try {
                        bout.close();
                    } catch (final IOException e) {
                        // TODO: 16/9/23 ignore Exception
                    }
                if (null != out)
                    try {
                        out.close();
                    } catch (final IOException e) {
                        // TODO: 16/9/23 ignore Exception
                    }
                if (null != in)
                    try {
                        in.close();
                    } catch (final IOException e) {
                        // TODO: 16/9/23 ignore Exception
                    }
                if (null != connection)
                    connection.disconnect();
            }
        }
    }

    private String encodeData(final String rawMessage) throws IOException {
        ByteArrayOutputStream os = new ByteArrayOutputStream(rawMessage.getBytes().length);
        GZIPOutputStream gos = new GZIPOutputStream(os);
        gos.write(rawMessage.getBytes());
        gos.close();
        byte[] compressed = os.toByteArray();
        os.close();
        return new String(Base64Coder.encode(compressed));
    }

    private String getCheckConfigure() throws ConnectErrorException {
        LogUtils.i(TAG, "getCheckConfigure");
        HttpURLConnection connection = null;
        InputStream in = null;
        try {
            final URL url = new URL(Cuckoo.with(mContext).getConfigureUrl());
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");

            int responseCode = connection.getResponseCode();
            in = connection.getInputStream();
            byte[] responseBody = slurp(in);
            in.close();
            in = null;

            String response = new String(responseBody, "UTF-8");
            if (responseCode != 200) {
                throw new ConnectErrorException("Response error.");
            }

            return response;
        } catch (IOException e) {
            throw new ConnectErrorException(e);
        } finally {
            if (null != in)
                try {
                    in.close();
                } catch (final IOException e) {
                    LogUtils.i(TAG, "getCheckConfigure close inputStream error:" + e.getMessage());
                }
            if (null != connection)
                connection.disconnect();
        }
    }

    // Worker will manage the (at most single) IO thread associated with
    // this EventQueue instance.
    // XXX: Worker class is unnecessary, should be just a subclass of HandlerThread
    private class Worker {

        private final Object mHandlerLock = new Object();
        private Handler mHandler;

        public Worker() {
            final HandlerThread thread =
                    new HandlerThread("com.guoxiaoxing.cuckoo.vtrack.EventQueue.Worker",
                            Thread.MIN_PRIORITY);
            thread.start();
            mHandler = new EventHandler(thread.getLooper());
        }

        public void sendMessage(Message msg) {
            synchronized (mHandlerLock) {
                if (mHandler == null) {
                    // We died under suspicious circumstances. Don't try to send any more events.
                    LogUtils.i(TAG, "Dead worker dropping a message: " + msg.what);
                } else {
                    mHandler.sendMessage(msg);
                }
            }
        }

        public void sendMessageOnce(Message msg, long delay) {
            synchronized (mHandlerLock) {
                if (mHandler == null) {
                    // We died under suspicious circumstances. Don't try to send any more events.
                    LogUtils.i(TAG, "Dead worker dropping a message: " + msg.what);
                } else {
                    if (!mHandler.hasMessages(msg.what)) {
                        mHandler.sendMessageDelayed(msg, delay);
                    }
                }
            }
        }

        private class EventHandler extends Handler {

            public EventHandler(Looper looper) {
                super(looper);
            }

            @Override
            public void handleMessage(Message msg) {
                try {
                    if (msg.what == FLUSH_QUEUE) {
                        sendData();
                    } else if (msg.what == CHECK_CONFIGURE) {
                        DecideMessages decideMessages = (DecideMessages) msg.obj;
                        try {
                            final String configureResult = getCheckConfigure();
                            try {
                                final JSONObject configureJson = new JSONObject(configureResult);

                                // 可视化埋点配置
                                final JSONObject eventBindings = configureJson.optJSONObject("event_bindings");
                                if (eventBindings != null && eventBindings.has("events") && eventBindings.get
                                        ("events") instanceof JSONArray) {
                                    decideMessages.setEventBindings(eventBindings.getJSONArray("events"));
                                }

                                // 可视化埋点管理界面地址
                                final String vtrackServer = configureJson.optString("vtrack_server_url");
                                // XXX: 为兼容老版本，这里无论是否为 null，都需要调用 setVTrackServer
                                decideMessages.setVTrackServer(vtrackServer);
                            } catch (JSONException e1) {
                                LogUtils.i(TAG, "Failed to load SDK configure with" + configureResult);
                            }
                        } catch (ConnectErrorException e) {
                            LogUtils.i(TAG, "Failed to get vtrack configure from SensorsAnalytics.", e);
                        }
                    } else {
                        LogUtils.i(TAG, "Unexpected message received by SensorsData worker: " + msg);
                    }
                } catch (final RuntimeException e) {
                    LogUtils.i(TAG, "Worker threw an unhandled exception", e);
                }
            }
        }
    }
}
