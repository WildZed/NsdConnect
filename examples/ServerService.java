
import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;

import com.koushikdutta.async.AsyncNetworkSocket;
import com.koushikdutta.async.AsyncServer;
import com.koushikdutta.async.AsyncServerSocket;
import com.koushikdutta.async.AsyncSocket;
import com.koushikdutta.async.ByteBufferList;
import com.koushikdutta.async.DataEmitter;
import com.koushikdutta.async.callback.CompletedCallback;
import com.koushikdutta.async.callback.DataCallback;
import com.koushikdutta.async.callback.ListenCallback;





public class AsyncServerExampleService extends Service
{
    public static final String LOGTAG = AsyncServerExampleService.class.getName();
    public static final int SERVER_PORT = 6000;

    private AsyncServer asyncServer;
    private AsyncNetworkSocket asyncClient;

    @Override
    public void onCreate() {
        asyncServer = new AsyncServer();
        asyncServer.listen(null, SERVER_PORT, listenCallback);
    }

    private ListenCallback listenCallback = new ListenCallback() {
        @Override
        public void onAccepted(AsyncSocket socket) {
            // this example service shows only a single server <-> client communication
            if (asyncClient != null) {
                asyncClient.close();
            }
            asyncClient = (AsyncNetworkSocket) socket;
            asyncClient.setDataCallback(new DataCallback() {
                @Override
                public void onDataAvailable(DataEmitter emitter, ByteBufferList bb) {
                    Log.i(LOGTAG, "Data received: " + bb.readString());
                }
            });
            asyncClient.setClosedCallback(new CompletedCallback() {
                @Override
                public void onCompleted(Exception ex) {
                    asyncClient = null;
                    Log.i(LOGTAG, "Client socket closed");
                }
            });
            Log.i(LOGTAG, "Client socket connected");
        }

        @Override
        public void onListening(AsyncServerSocket socket) {
            Log.i(LOGTAG, "Server listening on port " + socket.getLocalPort());
        }

        @Override
        public void onCompleted(Exception ex) {
            Log.i(LOGTAG, "Server socket closed");
        }
    };

    // call this method to send data to the client socket
    public void sendData(String message) {
        asyncClient.write(new ByteBufferList(message.getBytes()));
        Log.i(LOGTAG, "Data sent: " + message);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (asyncServer.isRunning()) {
            asyncServer.stop();
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}