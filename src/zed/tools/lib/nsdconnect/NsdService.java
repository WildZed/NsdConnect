package zed.tools.lib.nsdconnect;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.nsd.NsdServiceInfo;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.util.Log;
import android.widget.Toast;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.lang.ref.WeakReference;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.Enumeration;
import java.util.Hashtable;


// Requires the following in AndroidManifest.xml for your app.
// <service android:name=".app.NsdService" \>
// Include this if service is to be in a remote process.
// android:process=":remote" />

public class NsdService extends Service
{
    // Constants:
    private static final String            TAG                   = NsdService.class.getSimpleName();
    public final String                    SERVICE_THREAD_NAME   = TAG + ":ServiceThread";
    private static final int               FOREGROUND_SERVICE    = 101;
    // Messages from NsdServiceConnection.
    public static final int                MSG_REGISTER_CLIENT   = 1;
    public static final int                MSG_UNREGISTER_CLIENT = 2;
    public static final int                MSG_PAUSE             = 3;
    public static final int                MSG_RESUME            = 4;
    // Messages from service to client.
    public static final int                MSG_CONNECTED         = 10;
    public static final int                MSG_UNCONNECTED       = 11;
    public static final int                MSG_INFO              = 12;                                  // Talk back.
    // Messages between peers.
    public static final int                MSG_TEXT              = 20;
    public static final int                MSG_OBJECT            = 21;

    // Member variables:
    private String                         m_serviceName;
    private boolean                        m_isLocalService      = false;
    // private Context m_context;
    private Class<?>                       m_nsdServiceClient;
    private NsdHelper                      m_nsdHelper;
    private NotificationManager            m_notificationManager;
    private CommsServer                    m_commsServer;
    private Hashtable<String, CommsClient> m_commsClients        = new Hashtable<String, CommsClient>();
    private int                            m_localServerPort     = 0;
    private boolean                        m_connected           = false;
    // This is the object that receives interactions from clients. See RemoteService for a more complete example.
    private IBinder                        m_binder;
    // Target we publish for clients to send messages to IncomingHandler.
    private Messenger                      m_serviceMessenger;
    private Messenger                      m_clientMessenger;


    /**
     * Class for clients to access. Because we know this service always
     * runs in the same process as its clients, we don't need to deal with
     * IPC.
     */
    public class LocalBinder extends Binder
    {
        NsdService getService()
        {
            return NsdService.this;
        }
    }

    // Handler of incoming messages from clients.
    private static class IncomingHandler extends Handler
    {
        WeakReference<NsdService> m_nsdServiceWeakReference;


        public IncomingHandler( NsdService nsdService )
        {
            m_nsdServiceWeakReference = new WeakReference<NsdService>( nsdService );
        }


        @Override
        public void handleMessage( Message msg )
        {
            NsdService nsdService = m_nsdServiceWeakReference.get();

            if ( nsdService == null )
            {
                Log.w( TAG, "NSD service not started, attempt to send message failed." );
                return;
            }

            if ( !nsdService.sendMessage( msg ) )
            {
                super.handleMessage( msg );
            }
        }
    }


    // Called once when the service is first started.
    @Override
    public void onCreate()
    {
        // To debug service:
        // android.os.Debug.waitForDebugger();

        m_isLocalService = !isRemoteProcess();
        m_isLocalService = false;

        String serviceTypeStr = ( m_isLocalService ) ? "local" : "remote";

        Thread.currentThread().setName( SERVICE_THREAD_NAME );

        Log.i( TAG, TAG + " starting in thread '" + Thread.currentThread().getName() + "' as " + serviceTypeStr + " service." );

        m_notificationManager = (NotificationManager) getSystemService( NOTIFICATION_SERVICE );

        if ( m_isLocalService )
        {
            m_binder = new LocalBinder();
        }
        else
        {
            m_serviceMessenger = new Messenger( new IncomingHandler( this ) );
            m_binder = m_serviceMessenger.getBinder();
        }

        // Display a notification about us starting. We put an icon in the status bar.
        // showNotification( R.string.nsd_service_started );
        toast( R.string.nsd_service_started );
    }


    // This responds to a call of startService(), even if already running.
    @Override
    public int onStartCommand( Intent intent, int flags, int startId )
    {
        Log.i( TAG, "NSD service started with id " + startId + ": " + intent );

        if ( intent != null )
        {
            m_nsdServiceClient = intent.getClass();
            startServiceInForeground();
        }
        else
        {
            Log.w( TAG, "Unable to start NSD service in foreground, null intent." );
        }

        return START_STICKY;
    }


    @Override
    public void onDestroy()
    {
        tearDown();

        // Cancel the persistent notification.
        m_notificationManager.cancel( FOREGROUND_SERVICE );

        // Tell the user we stopped.
        toast( R.string.nsd_service_stopped );
    }


    @Override
    public IBinder onBind( Intent intent )
    {
        // Can we get the activity context from the Intent?
        return m_binder;
    }


    // @Override
    // public boolean onUnbind( Intent intent )
    // {
    // // Can we rebind?
    // return true;
    // }
    //
    //
    // @Override
    // public void onRebind( Intent intent )
    // {
    // // A client is binding to the service with bindService(),
    // // after onUnbind() has already been called.
    // }

    public void tearDown()
    {
        Log.d( TAG, "Tearing down " + TAG + " '" + m_serviceName + "'." );

        try
        {
            if ( m_commsServer != null )
            {
                m_commsServer.tearDown();
            }

            if ( m_nsdHelper != null )
            {
                m_nsdHelper.tearDown();
            }

            tearDownCommsClients();
        }
        catch ( RuntimeException e )
        {
            Log.e( TAG, "Run time exception: ", e );
            e.printStackTrace();
        }
        catch ( Exception e )
        {
            Log.e( TAG, "Unknown exception: ", e );
            e.printStackTrace();
        }

        m_commsServer = null;
        m_nsdHelper = null;
    }


    protected void finalize()
    {
        if ( m_nsdHelper != null )
        {
            tearDown();
        }
    }


    public int getLocalServerPort()
    {
        return m_localServerPort;
    }


    public void setLocalServerPort( int port )
    {
        m_localServerPort = port;
    }


    public boolean sendMessage( Message msg )
    {
        boolean processed = true;

        switch ( msg.what )
        {
            case MSG_REGISTER_CLIENT:
                registerClient( msg );
                break;

            case MSG_UNREGISTER_CLIENT:
                unregisterClient();
                break;

            case MSG_PAUSE:
                pause();
                break;

            case MSG_RESUME:
                resume();
                break;

            case MSG_TEXT:
            case MSG_OBJECT:
                sendMessageToClients( msg );
                break;

            case MSG_CONNECTED:
            case MSG_UNCONNECTED:
                Log.w( TAG, "Unexpected message " + msg.what + " from client." );
                break;

            default:
                processed = false;
                break;
        }

        return processed;
    }


    public void pause()
    {
        m_nsdHelper.stopDiscovery();
    }


    public void resume()
    {
        checkReconnectClients();
        m_nsdHelper.discoverServices();
    }


    private void startServiceInForeground()
    {
        Intent notificationIntent = new Intent( this, m_nsdServiceClient );
        //startActivity(new Intent(this, activity.class));
        String serviceName = getString( R.string.nsd_service_name );

        notificationIntent.setAction( "Listen" );
        notificationIntent.setFlags( Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK );

        PendingIntent pendingIntent = PendingIntent.getActivity( this, 0, notificationIntent, 0 );
        Bitmap icon = BitmapFactory.decodeResource( this.getResources(), R.drawable.nsd_service );
        Notification notification = new Notification.Builder( this ).setContentTitle( serviceName ).setTicker( serviceName )
                        .setContentText( serviceName ).setSmallIcon( R.drawable.nsd_service )
                        .setLargeIcon( Bitmap.createScaledBitmap( icon, 128, 128, false ) ).setContentIntent( pendingIntent )
                        .setOngoing( false ).build();

        startForeground( FOREGROUND_SERVICE, notification );
    }


    private void registerClient( Message msg )
    {
        Bundle bundle = msg.getData();

        String serviceName = bundle.getString( "serviceName" );

        if ( m_nsdHelper != null && !serviceName.equals( m_serviceName ) )
        {
            // Change of service name.
            tearDown();
        }

        m_serviceName = serviceName;
        m_clientMessenger = msg.replyTo;

        checkStartNetworkServiceDiscovery();
        checkStartServer();
        // Doesn't need pause to resume. It will just report a warning about discovery.
        resume();
        sendConnectedToUI();
    }


    private void unregisterClient()
    {
        // Could pause Network Service Discovery here?
        m_clientMessenger = null;
    }


    private NsdHelperHandler createNewNsdHelperHandler()
    {
        return new NsdHelperHandler()
        {
            public void onNewService( NsdServiceInfo serviceInfo )
            {
                connectToServer( serviceInfo.getHost(), serviceInfo.getPort() );
            }
        };
    }


    private void checkStartNetworkServiceDiscovery()
    {
        if ( m_nsdHelper == null )
        {
            m_nsdHelper = new NsdHelper( this, m_serviceName, createNewNsdHelperHandler() );
        }
    }


    private void checkStartServer()
    {
        if ( m_commsServer == null )
        {
            m_commsServer = new CommsServer();
        }
    }


    // private void stopServer()
    // {
    // m_commsServer.tearDown();
    // m_commsServer = null;
    // }

    private synchronized void addCommsClient( String hostAddress, CommsClient commsClient )
    {
        m_commsClients.put( hostAddress, commsClient );
    }


    private synchronized CommsClient getCommsClient( String hostAddress )
    {
        return m_commsClients.get( hostAddress );
    }


    private synchronized void removeCommsClient( String hostAddress )
    {
        m_commsClients.remove( hostAddress );
    }


    private synchronized Enumeration<String> getCommsClientKeys()
    {
        return m_commsClients.keys();
    }


    private void tearDownCommsClients()
    {
        Enumeration<String> hostAddresses = getCommsClientKeys();

        while ( hostAddresses.hasMoreElements() )
        {
            String hostAddress = hostAddresses.nextElement();
            CommsClient commsClient = getCommsClient( hostAddress );

            if ( commsClient != null )
            {
                commsClient.tearDown();
            }
        }
    }


    private void connectToServer( InetAddress inetAddress, int inetPort )
    {
        String hostAddress = inetAddress.getHostAddress();
        CommsClient commsClient = getCommsClient( hostAddress );

        if ( commsClient == null )
        {
            commsClient = new CommsClient( inetAddress, inetPort );
        }
        else
        {
            commsClient.checkReconnect();
        }
    }


    private void connectToSocket( Socket socket )
    {
        String hostAddress = socket.getInetAddress().getHostAddress();
        CommsClient commsClient = getCommsClient( hostAddress );

        if ( commsClient != null )
        {
            // We've been given a new connected socket to the peer,
            // so get rid of the old.
            Log.w( TAG, "Replacing " + CommsClient.class.getSimpleName() + " for connection from: " + hostAddress + ":" + socket.getPort() );

            commsClient.tearDown();
        }

        commsClient = new CommsClient( socket );
    }


    private void checkReconnectClients()
    {
        Enumeration<String> hostAddresses = getCommsClientKeys();

        while ( hostAddresses.hasMoreElements() )
        {
            String hostAddress = hostAddresses.nextElement();
            CommsClient commsClient = getCommsClient( hostAddress );

            if ( commsClient != null )
            {
                commsClient.checkReconnect();
            }
        }
    }


    private void sendMessageToClients( Message msg )
    {
        Enumeration<String> hostAddresses = getCommsClientKeys();

        while ( hostAddresses.hasMoreElements() )
        {
            String hostAddress = hostAddresses.nextElement();
            CommsClient commsClient = getCommsClient( hostAddress );

            if ( commsClient != null )
            {
                commsClient.sendMessage( msg );
            }
        }
    }


    private void sendMessageToUI( Message msg )
    {
        Log.i( TAG, "Updating message: " + msg.toString() );

        try
        {
            m_clientMessenger.send( msg );
        }
        catch ( RemoteException ex )
        {
            // The client has gone. Save message for later?
            Log.e( TAG, "Failed to send message to client: " + ex.toString() );
        }
    }


    private void sendTextToUI( String text )
    {
        Message msg = Message.obtain( null, MSG_TEXT );
        Bundle bundle = new Bundle();

        bundle.putString( "text", text );
        msg.setData( bundle );
        sendMessageToUI( msg );
    }


    private void sendInfoToUI( String info )
    {
        Message msg = Message.obtain( null, MSG_INFO );
        Bundle bundle = new Bundle();

        bundle.putString( "info", info );
        msg.setData( bundle );
        sendMessageToUI( msg );
    }


    private boolean isAnyClientConnected()
    {
        boolean connected = false;
        Enumeration<String> hostAddresses = getCommsClientKeys();

        while ( hostAddresses.hasMoreElements() )
        {
            String hostAddress = hostAddresses.nextElement();
            CommsClient commsClient = getCommsClient( hostAddress );

            if ( commsClient != null && commsClient.isConnected() )
            {
                connected = true;
                break;
            }
        }

        return connected;
    }


    private void notifyClientChange( boolean connected )
    {
        // Newly connected is easy.
        // Otherwise we have to check if there are any remaining connected CommsClients.
        if ( !connected )
        {
            connected = isAnyClientConnected();
        }

        updateConnected( connected );
    }


    private void updateConnected( boolean connected )
    {
        if ( connected == m_connected )
        {
            return;
        }
        
        m_connected = connected;

        sendConnectedToUI();
    }


    private void sendConnectedToUI()
    {
        Log.i( TAG, "Updating client about connection: " + m_connected );

        sendMessageToUI( Message.obtain( null, ( m_connected ) ? MSG_CONNECTED : MSG_UNCONNECTED ) );
    }


    private boolean isRemoteProcess()
    {
        String processName = this.getApplication().getApplicationContext().getApplicationInfo().processName;

        Log.d( TAG, "Process name is '" + processName + "'." );

        return processName.endsWith( ":remote" );
    }


    private void toast( int strId )
    {
        String toastStr = getString( strId );

        Toast.makeText( this, toastStr, Toast.LENGTH_SHORT ).show();
        Log.i( TAG, toastStr + "." );
    }


    private void showNotification( int strId )
    {
        // In this sample, we'll use the same text for the ticker and the expanded notification.
        CharSequence text = getString( strId );

        // The PendingIntent to launch our activity if the user selects this notification.
        Intent activityIntent = new Intent( this, m_nsdServiceClient );
        PendingIntent contentIntent = PendingIntent.getActivity( this, 0, activityIntent, 0 );

        // Set the info for the views that show in the notification panel.
        Notification notification = new Notification.Builder( this ).setSmallIcon( R.drawable.nsd_service )
                        // the status icon
                        .setTicker( text ).setWhen( System.currentTimeMillis() ).setContentTitle( getText( R.string.nsd_service_name ) )
                        .setContentText( text ).setContentIntent( contentIntent ).build();

        // Send the notification.
        // We use a string id because it is a unique number. We use it later to cancel.
        m_notificationManager.notify( strId, notification );
    }


    // This is our local server to accept incoming connections from our peers.
    private class CommsServer
    {
        private final String SERVER_TAG         = TAG + ":" + CommsServer.class.getSimpleName();
        public final String  SERVER_THREAD_NAME = SERVER_TAG + ":ServerThread";

        ServerSocket         m_serverSocket     = null;
        Thread               m_serverThread     = null;


        public CommsServer()
        {
            m_serverThread = new Thread( new ServerThread(), SERVER_THREAD_NAME );
            m_serverThread.start();
        }


        public void tearDown()
        {
            Log.d( SERVER_TAG, "Tearing down " + SERVER_TAG + " for '" + m_serviceName + "'." );

            m_nsdHelper.unregisterService();
            m_serverThread.interrupt();

            try
            {
                m_serverSocket.close();
            }
            catch ( IOException ioe )
            {
                Log.e( SERVER_TAG, "Error when closing server socket." );
            }

            m_serverThread = null;
        }


        // The server services connection requests to us.
        // It accepts sockets from other peers, which then becomes a CommsClient socket
        // to send and receive messages to/from a peer.
        // Alternatively a connection request to a remote service is accepted and that
        // become a CommsClient socket to send and receive messages to/from a peer.
        class ServerThread implements Runnable
        {
            // @Override
            public void run()
            {
                while ( !Thread.currentThread().isInterrupted() )
                {
                    try
                    {
                        // Since discovery will happen via Nsd, we don't need to care which port is
                        // used. Just grab an available one and advertise it via Nsd.
                        m_serverSocket = new ServerSocket( m_localServerPort );
                        setLocalServerPort( m_serverSocket.getLocalPort() );
                        Log.d( SERVER_TAG, "ServerSocket created, awaiting connection on port:" + m_localServerPort );
                        m_nsdHelper.registerService( m_localServerPort );

                        while ( !Thread.currentThread().isInterrupted() )
                        {
                            Log.d( SERVER_TAG, "Awaiting new connection on ServiceSocket..." );
                            Socket socket = m_serverSocket.accept();
                            Log.d( SERVER_TAG, "Connected." );

                            // Create a new CommsClient using the socket we've just accepted.
                            connectToSocket( socket );
                        }
                    }
                    catch ( IOException e )
                    {
                        Log.w( SERVER_TAG, "ServerSocket exception (can occur when closing ServerSocket): ", e );
                        e.printStackTrace();
                    }
                }
            }
        }
    }

    // This is the client to manage a connection to a peer, to send and receive.
    private class CommsClient
    {
        private final String       CLIENT_TAG            = TAG + ":" + CommsClient.class.getSimpleName();
        public final String        RECEIVING_THREAD_NAME = CLIENT_TAG + ":ReceivingThread";

        private InetAddress        m_inetAddress         = null;
        private int                m_inetPort            = 0;
        private Socket             m_socket              = null;
        private ObjectInputStream  m_objectInputStream   = null;
        private ObjectOutputStream m_objectOutputStream  = null;

        // private Thread m_sendingThread = null;
        private Thread             m_receivingThread     = null;


        public CommsClient( InetAddress inetAddress, int inetPort )
        {
            m_inetAddress = inetAddress;
            m_inetPort = inetPort;

            String hostAddress = m_inetAddress.getHostAddress();

            Log.d( CLIENT_TAG, "Creating " + CLIENT_TAG + " for connection to: " + hostAddress + ":" + m_inetPort );

            addCommsClient( hostAddress, this );
            createReceivingThread();
        }


        public CommsClient( Socket socket )
        {
            m_socket = socket;
            m_inetAddress = m_socket.getInetAddress();
            m_inetPort = m_socket.getPort();

            String hostAddress = m_inetAddress.getHostAddress();

            Log.d( CLIENT_TAG, "Creating " + CLIENT_TAG + " for connection from: " + hostAddress + ":" + m_inetPort );

            addCommsClient( hostAddress, this );
            createReceivingThread();
        }


        public void tearDown()
        {
            String hostAddress = m_inetAddress.getHostAddress();

            Log.d( TAG, "Tearing down " + CLIENT_TAG + " for:" + hostAddress + ":" + m_inetPort );

            removeCommsClient( hostAddress );
            closeIOStreams();
            closeSocket();
            interruptThreads();
            updateConnected( false );
        }


        public synchronized boolean isConnected()
        {
            return ( m_socket != null && m_socket.isConnected() );
        }


        public void checkReconnect()
        {
            if ( isConnected() )
            {
                return;
            }

            Log.d( TAG, "Reconnecting " + CLIENT_TAG + " for:" + m_inetAddress.getHostAddress() + ":" + m_inetPort );

            closeSocket();
            interruptThreads();
            createReceivingThread();
        }


        // Sending messages to our peer.
        public void sendMessage( Message msg )
        {
            if ( m_socket == null )
            {
                Log.w( CLIENT_TAG, "Socket is null!" );
                return;
            }

            String text = "";

            switch ( msg.what )
            {
                case MSG_TEXT:
                    text = msg.getData().getString( "text" );
                    break;

                // case MSG_OBJECT:
                // Log.w( CLIENT_TAG, "Unhandled message id: " + msg.what );
                // break;

                default:
                    Log.w( CLIENT_TAG, "Unhandled message id: " + msg.what );
                    return;
            }

            try
            {
                m_objectOutputStream.writeObject( text );
                m_objectOutputStream.flush();
                sendInfoToUI( "Sent message with id " + msg.what + " to client " + m_inetAddress.getHostAddress() + "." );
            }
            catch ( UnknownHostException ex )
            {
                Log.d( CLIENT_TAG, "Unknown Host!", ex );
            }
            catch ( IOException ex )
            {
                Log.d( CLIENT_TAG, "I/O Exception!", ex );
            }
            catch ( Exception ex )
            {
                Log.d( CLIENT_TAG, "Miscellaneous Exception!", ex );
            }

            Log.d( CLIENT_TAG, "Client sent message '" + text + "'." );
        }


        private void createReceivingThread()
        {
            m_receivingThread = new Thread( new ReceivingThread(), RECEIVING_THREAD_NAME );
            m_receivingThread.start();
        }


        private synchronized boolean createIOStreams()
        {
            if ( m_socket == null || m_socket.isClosed() )
            {
                Log.e( CLIENT_TAG, "Unable to create IO streams, bad socket." );
                return false;
            }

            boolean ok = false;

            try
            {
                // For some reason this doesn't create the streams if the input stream
                // is created first!
                m_objectOutputStream = new ObjectOutputStream( m_socket.getOutputStream() );
                m_objectInputStream = new ObjectInputStream( m_socket.getInputStream() );
                ok = true;
            }
            catch ( IOException ex )
            {
                Log.e( CLIENT_TAG, "IO exception creating IO streams: ", ex );
            }
            catch ( Exception ex )
            {
                Log.e( CLIENT_TAG, "Other exception creating IO streams: ", ex );
            }

            return ok;
        }


        private synchronized void closeIOStreams()
        {
            try
            {
                if ( m_objectInputStream != null )
                {
                    m_objectInputStream.close();
                }

                if ( m_objectOutputStream != null )
                {
                    m_objectOutputStream.close();
                }
            }
            catch ( Exception ex )
            {}

            m_objectInputStream = null;
            m_objectOutputStream = null;
        }


        private void interruptThreads()
        {
            if ( m_receivingThread != null )
            {
                m_receivingThread.interrupt();
            }

            m_receivingThread = null;
        }


        private synchronized void setSocket( Socket socket )
        {
            if ( socket == null )
            {
                Log.d( TAG, "Setting a null socket." );
            }
            else
            {
                Log.d( TAG, "Setting a new socket." );
            }

            if ( m_socket != null )
            {
                Log.w( TAG, "Replacing the existing socket." );

                if ( !m_socket.isClosed() )
                {
                    try
                    {
                        m_socket.close();
                    }
                    catch ( IOException e )
                    {
                        e.printStackTrace();
                    }
                }
            }

            m_socket = socket;
        }


        private boolean checkOpenSocket()
        {
            boolean socketOk = false;

            try
            {
                if ( isConnected() )
                {
                    Log.d( TAG, "Socket already initialised, skipping!" );
                }
                else
                {
                    setSocket( new Socket( m_inetAddress, m_inetPort ) );

                    Log.d( TAG, "Client-side socket initialized." );
                }

                socketOk = true;
            }
            catch ( UnknownHostException e )
            {
                Log.d( TAG, "Initializing socket failed, UHE", e );
            }
            catch ( IOException e )
            {
                Log.d( TAG, "Initializing socket failed, IOE.", e );
            }

            notifyClientChange( socketOk );

            return socketOk;
        }


        private synchronized void closeSocket()
        {
            if ( m_socket == null )
            {
                return;
            }

            try
            {
                m_socket.close();
            }
            catch ( IOException ioe )
            {
                Log.e( TAG, "Error when closing socket." );
            }

            m_socket = null;
            notifyClientChange( false );
        }


        // The receiving thread asynchronously listens for and receives messages from a peer.
        class ReceivingThread implements Runnable
        {
            // @Override
            public void run()
            {
                if ( !checkOpenSocket() || !createIOStreams() )
                {
                    tearDown();
                    return;
                }

                try
                {
                    while ( !Thread.currentThread().isInterrupted() )
                    {
                        String text = (String) m_objectInputStream.readObject();

                        if ( text != null )
                        {
                            Log.d( CLIENT_TAG, "Read from the stream '" + text + "'." );

                            sendTextToUI( text );
                        }
                        else
                        {
                            Log.d( CLIENT_TAG, "The nulls! The nulls!" );
                            break;
                        }
                    }
                }
                catch ( IOException e )
                {
                    Log.e( CLIENT_TAG, "Receiving loop io error: ", e );
                }
                catch ( Exception e )
                {
                    Log.e( CLIENT_TAG, "Receiving loop miscellaneous exception error: ", e );
                }

                tearDown();
            }
        }
    }

}

// private synchronized void updateMessages( String msg, boolean local )
// {
// if ( local )
// {
// msg = "me: " + msg;
// }
// else
// {
// msg = "them: " + msg;
// }
//
// Log.i( TAG, "Updating message: " + msg );
//
// Bundle messageBundle = new Bundle();
// messageBundle.putString( "msg", msg );
//
// Message message = new Message();
// message.setData( messageBundle );
// m_updateHandler.sendMessage( message );
// }

// private void sendMessageToUI( String text )
// {
// try
// {
// m_clientData.m_messenger.send( Message.obtain( null, MSG_SEND_TEXT, text ) );
// }
// catch ( RemoteException ex )
// {
// // The client has gone. Save message for later?
//
// }
// }

// I've no idea of the point of the sending thread, apart from to start the receiving thread.
// class SendingThread implements Runnable
// {
// BlockingQueue<String> m_messageQueue;
// private final int QUEUE_CAPACITY = 10;
//
//
// public SendingThread()
// {
// m_messageQueue = new ArrayBlockingQueue<String>( QUEUE_CAPACITY );
// }
//
//
// // @Override
// public void run()
// {
// if ( ! checkOpenSocket() )
// {
// return;
// }
//
// m_receivingThread = new Thread( new ReceivingThread(), RECEIVING_THREAD_NAME );
// m_receivingThread.start();
//
// while ( ! Thread.currentThread().isInterrupted() )
// {
// try
// {
// String msg = m_messageQueue.take();
//
// Log.d( CLIENT_TAG, "Sending message from SendingThread: " + msg );
//
// sendMessage( msg );
// }
// catch ( InterruptedException ie )
// {
// Log.d( CLIENT_TAG, "Message sending loop interrupted, exiting." );
// }
// }
// }
// }
// try
// {
// if ( m_socket == null )
// {
// Log.d( CLIENT_TAG, "Socket is null!" );
// }
// else if ( m_socket.getOutputStream() == null )
// {
// Log.d( CLIENT_TAG, "Socket output stream is null!" );
// }
//
// Message msg = Message.;
//
// PrintWriter out = new PrintWriter( new BufferedWriter( new OutputStreamWriter( m_socket.getOutputStream() ) ), true );
// out.println( msg );
// out.flush();
// updateMessages( msg, true );
// }
// catch ( UnknownHostException e )
// {
// Log.d( CLIENT_TAG, "Unknown Host!", e );
// }
// catch ( IOException e )
// {
// Log.d( CLIENT_TAG, "I/O Exception!", e );
// }
// catch ( Exception e )
// {
// Log.d( CLIENT_TAG, "Misc Exception!", e );
// }
//
// Log.d( CLIENT_TAG, "Client sent message: " + msg );
