package zed.tools.lib.nsdconnect;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
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
    public static final String             SERVICE_NAME           = "serviceName";
    public static final String             CLIENT_PACKAGE         = "clientPackage";
    public static final String             CLIENT_CLASS           = "clientClass";
    public static final String             ACTION_STOP_SERVICE    = "STOP";
    public static final String             ACTION_START_SERVICE   = "START";
    public static final String             ACTION_REFRESH_SERVICE = "REFRESH";
    public static final String             ACTION_DEBUG           = "DEBUG";

    // Constants:
    private static final String            TAG                    = NsdService.class.getSimpleName();
    private static final String            SERVICE_THREAD_NAME    = TAG + ":ServiceThread";
    private static final int               FOREGROUND_SERVICE     = 101;
    // private static final int REMOTE_CLIENT_RETRIES = 8;
    // private static final int               REMOTE_CLIENT_WAIT_MS  = 800;
    // Messages from NsdServiceConnection.
    public static final int                MSG_REGISTER_CLIENT    = 1;
    public static final int                MSG_UNREGISTER_CLIENT  = 2;
    public static final int                MSG_PAUSE              = 3;
    public static final int                MSG_RESUME             = 4;
    public static final int                MSG_REFRESH            = 5;
    // Messages from service to client.
    public static final int                MSG_CONNECTED          = 10;
    public static final int                MSG_UNCONNECTED        = 11;
    public static final int                MSG_INFO               = 12;                                  // Talk back.
    // Messages between peers.
    public static final int                MSG_TEXT               = 20;
    public static final int                MSG_OBJECT             = 21;

    // Member variables:
    private String                         m_serviceName;
    private boolean                        m_isLocalService       = false;
    // private Context m_context;
    private String                         m_nsdServiceClientPackage;
    private String                         m_nsdServiceClientClass;
    private String                         m_nsdServiceClientFullClass;
    private NsdHelper                      m_nsdHelper;
    private BroadcastReceiver              m_nsdBroadcastReceiver;
    private NotificationManager            m_notificationManager;
    private CommsServer                    m_commsServer;
    private Hashtable<String, CommsClient> m_commsClients         = new Hashtable<String, CommsClient>();
    private int                            m_localServerPort      = 0;
    private boolean                        m_connected            = false;
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
        android.os.Debug.waitForDebugger();

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

        addBroadcastReceiver();

        // Display a notification about us starting. We put an icon in the status bar.
        // showNotification( R.string.nsd_service_started );
        toast( R.string.nsd_service_started );
    }


    // This responds to a call of startService(), even if already running.
    @Override
    public int onStartCommand( Intent intent, int flags, int startId )
    {
        if ( intent == null )
        {
            Log.w( TAG, "Call to onStartCommand with null intent, id " + startId + "." );

            return START_NOT_STICKY;
        }

        String action = intent.getAction();

        if ( ACTION_START_SERVICE.equals( action ) )
        {
            Log.i( TAG, "NSD service started with id " + startId + ": " + intent );

            m_nsdServiceClientPackage = intent.getStringExtra( CLIENT_PACKAGE );
            m_nsdServiceClientClass = intent.getStringExtra( CLIENT_CLASS );
            m_nsdServiceClientFullClass = m_nsdServiceClientPackage + "." + m_nsdServiceClientClass;
            m_serviceName = intent.getStringExtra( SERVICE_NAME );

            startServiceInForeground();
        }
        else if ( ACTION_REFRESH_SERVICE.equals( action ) )
        {
            Log.i( TAG, "NSD service refreshed with id " + startId + ": " + intent );

            refreshAll();
        }
        else if ( ACTION_STOP_SERVICE.equals( action ) )
        {
            Log.i( TAG, "NSD service stopped with id " + startId + ": " + intent );

            // onDestroy();
            stopSelf();
        }
        else if ( ACTION_DEBUG.equals( action ) )
        {
            Log.i( TAG, "NSD service attach debugger with id " + startId + ": " + intent );

            // To debug service:
            android.os.Debug.waitForDebugger();
        }
        else
        {
            Log.w( TAG, "NSD service unrecognised action '" + action + "' with id " + startId + ": " + intent );
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
            if ( m_nsdBroadcastReceiver != null )
            {
                unregisterReceiver( m_nsdBroadcastReceiver );
            }

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

            case MSG_REFRESH:
                refresh();
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


    public void refresh()
    {
        checkReconnectClients();
    }


    public void pause()
    {
        if ( m_nsdHelper != null && m_nsdHelper.isServiceDiscoveryActive() )
        {
            m_nsdHelper.stopDiscovery();
        }
    }


    public void resume()
    {
        checkStartNetworkServiceDiscovery();
        checkStartServer();
        refresh();

        if ( m_nsdHelper != null && !m_nsdHelper.isServiceDiscoveryActive() )
        {
            m_nsdHelper.discoverServices();
        }

        notifyClientChange( false, true );
    }


    public void refreshAll()
    {
        pause();
        resume();
    }


    private PendingIntent getServicePendingIntent( String action )
    {
        Intent actionIntent = new Intent( this, NsdService.class );

        actionIntent.setAction( action );

        PendingIntent actionPendingIntent = PendingIntent.getService( this, 0, actionIntent, 0 );

        return actionPendingIntent;
    }


    private PendingIntent getClientActivityPendingIntent( String action )
    {
        Intent notificationIntent = new Intent();
        ComponentName nsdServiceClientComponent = new ComponentName( m_nsdServiceClientPackage, m_nsdServiceClientFullClass );

        notificationIntent.setComponent( nsdServiceClientComponent );
        notificationIntent.setAction( action );
        notificationIntent.setFlags( Intent.FLAG_ACTIVITY_NEW_TASK ); // | Intent.FLAG_ACTIVITY_CLEAR_TASK );

        // RemoteViews notificationView = new RemoteViews( getPackageName(), R.drawable.nsd_default );

        PendingIntent actionPendingIntent = PendingIntent.getActivity( this, 0, notificationIntent, 0 );

        // notification.contentView = notificationView;

        // Intent switchIntent = new Intent( this, m_nsdServiceClient );
        // PendingIntent pendingSwitchIntent = PendingIntent.getBroadcast( this, 0, switchIntent, 0 );

        // notificationView.setOnClickPendingIntent( R.id.buttonswitch, pendingSwitchIntent );

        return actionPendingIntent;
    }


    private void startServiceInForeground()
    {
        // startActivity(new Intent(this, activity.class));
        String generalServiceName = getString( R.string.nsd_service_name );
        String serviceName = m_serviceName;
        Bitmap icon = BitmapFactory.decodeResource( this.getResources(), R.drawable.ic_nsd_service );

        if ( serviceName == null )
        {
            serviceName = generalServiceName;
        }

        Notification notification = new Notification.Builder( this ).setContentTitle( generalServiceName ).setTicker( serviceName )
                        .setContentText( serviceName ).setSmallIcon( R.drawable.ic_nsd_service )
                        .setLargeIcon( Bitmap.createScaledBitmap( icon, 128, 128, false ) ).setOngoing( false )
                        .setContentIntent( getClientActivityPendingIntent( Intent.ACTION_VIEW ) )
                        .addAction( R.drawable.ic_nsd_stop, null, getServicePendingIntent( ACTION_STOP_SERVICE ) )
                        .addAction( R.drawable.ic_nsd_refresh, null, getServicePendingIntent( ACTION_REFRESH_SERVICE ) )
                        .addAction( R.drawable.ic_nsd_debug, null, getServicePendingIntent( ACTION_DEBUG ) ).build();

        startForeground( FOREGROUND_SERVICE, notification );
    }


    private void registerClient( Message msg )
    {
        Bundle bundle = msg.getData();

        String serviceName = bundle.getString( SERVICE_NAME );

        if ( m_nsdHelper != null && !serviceName.equals( m_serviceName ) )
        {
            // Change of service name.
            tearDown();
        }

        m_serviceName = serviceName;
        m_clientMessenger = msg.replyTo;

        // Doesn't need pause to resume. It will just report a warning about discovery.
        resume();
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


            public void onLostService( NsdServiceInfo serviceInfo )
            {
                removeServiceIfLost( serviceInfo.getHost(), serviceInfo.getPort() );
            }
        };
    }


    // <receiver android:name="zed.tools.lib.nsdconnect.NsdService$NsdBroadcastReceiver" >
    // <intent-filter>
    // <action android:name="android.net.wifi.supplicant.CONNECTION_CHANGE" />
    // <action android:name="android.net.wifi.STATE_CHANGE" />
    // </intent-filter>
    // </receiver>

    private BroadcastReceiver createNewNsdBroadcastReceiver()
    {
        return new BroadcastReceiver()
        {
            private final String BRCVR_TAG = TAG + ":NsdBroadcastReceiver";


            @Override
            public void onReceive( Context context, Intent intent )
            {
                // WifiManager wifiManager = (WifiManager) context.getSystemService( Context.WIFI_SERVICE );
                NetworkInfo networkInfo = intent.getParcelableExtra( WifiManager.EXTRA_NETWORK_INFO );

                if ( networkInfo == null )
                {
                    return;
                }

                Log.d( BRCVR_TAG, "Type: " + networkInfo.getType() + " State: " + networkInfo.getState() );

                if ( networkInfo.getType() == ConnectivityManager.TYPE_WIFI )
                {
                    NetworkInfo.State wifiState = networkInfo.getState();

                    // Get the different network states.
                    switch ( wifiState )
                    {
                        case CONNECTING:
                            break;

                        case CONNECTED:
                            resume();
                            break;

                        case DISCONNECTING:
                        case DISCONNECTED:
                            pause();
                            closeCommsClients();
                            break;

                        default:
                            break;
                    }
                }
            }
        };
    }


    private void addBroadcastReceiver()
    {
        IntentFilter wirelessIntentFilter = new IntentFilter();

        wirelessIntentFilter.addAction( "android.net.wifi.supplicant.CONNECTION_CHANGE" );
        wirelessIntentFilter.addAction( "android.net.wifi.STATE_CHANGE" );
        m_nsdBroadcastReceiver = createNewNsdBroadcastReceiver();
        registerReceiver( m_nsdBroadcastReceiver, wirelessIntentFilter );
    }


    private void checkStartNetworkServiceDiscovery()
    {
        if ( m_nsdHelper == null && m_serviceName != null )
        {
            m_nsdHelper = new NsdHelper( this, m_serviceName, createNewNsdHelperHandler() );
        }
    }


    private void checkStartServer()
    {
        if ( m_commsServer == null && m_nsdHelper != null )
        {
            m_commsServer = new CommsServer();
        }
    }


    // private void stopServer()
    // {
    // m_commsServer.tearDown();
    // m_commsServer = null;
    // }

    private synchronized CommsClient addCommsClient( String hostAddress, CommsClient commsClient )
    {
        return m_commsClients.put( hostAddress, commsClient );
    }


    private synchronized CommsClient getCommsClient( String hostAddress )
    {
        return m_commsClients.get( hostAddress );
    }


    private synchronized CommsClient removeCommsClient( String hostAddress )
    {
        return m_commsClients.remove( hostAddress );
    }


    private synchronized CommsClient removeCommsClient( String hostAddress, CommsClient commsClient )
    {
        CommsClient storedCommsClient = getCommsClient( hostAddress );

        if ( storedCommsClient == commsClient )
        {
            m_commsClients.remove( hostAddress );
            storedCommsClient = null;
        }

        return storedCommsClient;
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

            if ( commsClient == null )
            {
                Log.e( TAG, "Null client in remote clients list, this shouldn't happen." );

                removeCommsClient( hostAddress );
            }
            else
            {
                commsClient.tearDown();
            }
        }
    }


    // Close the IO for the CommsClient leaving an empty shell that can be reconnected later.
    private void closeCommsClients()
    {
        Enumeration<String> hostAddresses = getCommsClientKeys();

        while ( hostAddresses.hasMoreElements() )
        {
            String hostAddress = hostAddresses.nextElement();
            CommsClient commsClient = getCommsClient( hostAddress );

            if ( commsClient == null )
            {
                Log.e( TAG, "Null client in remote clients list, this shouldn't happen." );

                removeCommsClient( hostAddress );
            }
            else
            {
                commsClient.close();
            }
        }
    }


    private synchronized void connectToServer( InetAddress inetAddress, int inetPort )
    {
        String hostAddress = inetAddress.getHostAddress();
        CommsClient commsClient = getCommsClient( hostAddress );

        if ( commsClient == null )
        {
            // This adds to the enclosing class' list of clients.
            commsClient = new CommsClient( inetAddress, inetPort );
        }
        else
        {
            commsClient.checkReconnect();
        }
    }


    private synchronized void connectToSocket( Socket socket )
    {
        String hostAddress = socket.getInetAddress().getHostAddress();
        CommsClient commsClient = getCommsClient( hostAddress );

        if ( commsClient != null && !commsClient.isConnected() )
        {
            // We've been given a new connected socket to the peer, so get rid of the old.
            Log.w( TAG, "Replacing " + CommsClient.class.getSimpleName() + " for connection from: " + hostAddress + ":" + socket.getPort() );

            commsClient.tearDown();
            commsClient = null;
        }

        if ( commsClient == null )
        {
            // This adds to the enclosing class' list of clients.
            commsClient = new CommsClient( socket );
        }
        else
        {
            // This will cause the extra CommsClient the other end to fail to connect and die.
            closeSocket( socket );
        }
    }


    private void closeSocket( Socket socket )
    {
        if ( socket == null )
        {
            return;
        }

        try
        {
            socket.close();
        }
        catch ( IOException ioe )
        {
            Log.e( TAG, "Error when closing socket: " + ioe.toString() );
        }
        catch ( Exception ex )
        {
            Log.e( TAG, "Error when closing socket: " + ex.toString() );
        }
    }


    private void removeServiceIfLost( InetAddress inetAddress, int inetPort )
    {
        if ( inetAddress == null )
        {
            Log.e( TAG, "Service lost with no IP address." );

            return;
        }

        String hostAddress = inetAddress.getHostAddress();
        CommsClient commsClient = getCommsClient( hostAddress );

        if ( commsClient != null )
        {
            commsClient.setServicePublished( false );

            if ( !commsClient.isConnected() )
            {
                // We've been given a new connected socket to the peer,
                // so get rid of the old.
                Log.w( TAG, "Removing " + CommsClient.class.getSimpleName() + " for connection from: " + hostAddress + ":" + inetPort );

                commsClient.tearDown();
            }
        }
    }


    private void checkReconnectClients()
    {
        Enumeration<String> hostAddresses = getCommsClientKeys();

        while ( hostAddresses.hasMoreElements() )
        {
            String hostAddress = hostAddresses.nextElement();
            CommsClient commsClient = getCommsClient( hostAddress );

            if ( commsClient == null )
            {
                Log.e( TAG, "Null client in remote clients list, this shouldn't happen." );

                removeCommsClient( hostAddress );
            }
            else
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
        Log.i( TAG, "Updating message: " + msg.getData().toString() );

        if ( m_clientMessenger == null )
        {
            Log.w( TAG, "Client messenger is null." );

            return;
        }

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


    private void notifyClientChange( boolean connectedKnown, boolean forceUpdate )
    {
        boolean connected = connectedKnown;

        // Newly connected is easy.
        // Otherwise we have to check if there are any remaining connected CommsClients.
        if ( !connectedKnown )
        {
            connected = isAnyClientConnected();
        }

        updateConnected( connected, forceUpdate );
    }


    private void updateConnected( boolean connected, boolean forceUpdate )
    {
        if ( forceUpdate || connected != m_connected )
        {
            m_connected = connected;

            sendConnectedToUI();
        }
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


    // private void showNotification( int strId )
    // {
    // // In this sample, we'll use the same text for the ticker and the expanded notification.
    // CharSequence text = getString( strId );
    //
    // // The PendingIntent to launch our activity if the user selects this notification.
    // Intent activityIntent = new Intent( this, m_nsdServiceClient );
    // PendingIntent contentIntent = PendingIntent.getActivity( this, 0, activityIntent, 0 );
    //
    // // Set the info for the views that show in the notification panel.
    // Notification notification = new Notification.Builder( this ).setSmallIcon( R.drawable.ic_nsd_service )
    // // the status icon
    // .setTicker( text ).setWhen( System.currentTimeMillis() ).setContentTitle( getText( R.string.nsd_service_name ) )
    // .setContentText( text ).setContentIntent( contentIntent ).build();
    //
    // // Send the notification.
    // // We use a string id because it is a unique number. We use it later to cancel.
    // m_notificationManager.notify( strId, notification );
    // }

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

            try
            {
                if ( m_nsdHelper != null )
                {
                    m_nsdHelper.unregisterService();
                }

                m_serverThread.interrupt();
            }
            catch ( Exception ex )
            {
                Log.e( SERVER_TAG, "Error when tearing down " + SERVER_TAG + ": " + ex.toString() );
            }

            closeServerSocket();
            m_serverThread = null;
        }


        private void closeServerSocket()
        {
            if ( m_serverSocket == null )
            {
                return;
            }

            try
            {
                m_serverSocket.close();
            }
            catch ( IOException ioe )
            {
                Log.e( SERVER_TAG, "Error when closing server socket: " + ioe.toString() );
            }
            catch ( Exception ex )
            {
                Log.e( SERVER_TAG, "Error when closing server socket: " + ex.toString() );
            }
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
                            Log.d( SERVER_TAG, "Awaiting new connection on ServerSocket..." );
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
        private boolean            m_servicePublished    = false;
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

            addCommsClient();
            createReceivingThread();
        }


        public CommsClient( Socket socket )
        {
            m_socket = socket;
            m_inetAddress = m_socket.getInetAddress();
            m_inetPort = m_socket.getPort();

            String hostAddress = m_inetAddress.getHostAddress();

            Log.d( CLIENT_TAG, "Creating " + CLIENT_TAG + " for connection from: " + hostAddress + ":" + m_inetPort );

            addCommsClient();
            createReceivingThread();
        }


        public synchronized void tearDown()
        {
            String hostAddress = m_inetAddress.getHostAddress();

            Log.d( TAG, "Tearing down " + CLIENT_TAG + " for:" + hostAddress + ":" + m_inetPort );

            removeCommsClient( hostAddress, this );
            close();
        }


        public synchronized boolean isConnected()
        {
            return ( m_socket != null && m_socket.isConnected() );
        }


        public boolean getServicePublished()
        {
            return m_servicePublished;
        }


        public void setServicePublished( boolean published )
        {
            m_servicePublished = published;
        }


        public void checkReconnect()
        {
            if ( isConnected() )
            {
                return;
            }

            Log.d( TAG, "Reconnecting " + CLIENT_TAG + " for:" + m_inetAddress.getHostAddress() + ":" + m_inetPort );

            close();
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

                Log.d( CLIENT_TAG, "Client sent message '" + text + "'." );
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
        }


        private void addCommsClient()
        {
            String hostAddress = m_inetAddress.getHostAddress();
            CommsClient storedCommsClient = NsdService.this.addCommsClient( hostAddress, this );

            if ( storedCommsClient != null )
            {
                // Replaced CommsClient. Make sure it is destroyed.
                storedCommsClient.tearDown();
            }
        }


        public synchronized void close()
        {
            closeAllIO();
            interruptThreads();
        }


        private void createReceivingThread()
        {
            m_servicePublished = true;
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


        private synchronized boolean checkOpenSocket()
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

            notifyClientChange( socketOk, false );

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
            notifyClientChange( false, false );
        }


        private synchronized void closeAllIO()
        {
            closeIOStreams();
            closeSocket();
        }


        // The receiving thread asynchronously listens for and receives messages from a peer.
        class ReceivingThread implements Runnable
        {
            // @Override
            public void run()
            {
                // for ( int retries = 0; m_servicePublished && retries < REMOTE_CLIENT_RETRIES && !Thread.currentThread().isInterrupted();
                // retries++ )
                // {
                // if ( retries != 0 )
                // {
                // closeAllIO();
                // sleep( REMOTE_CLIENT_WAIT_MS );
                // }

                createAndDoIOLoop();
                // }

                if ( getServicePublished() )
                {
                    // Leave the shell of the CommsClient running to allow easy reconnect.
                    close();
                }
                else
                {
                    // Service was not published. We were just trying to reconnect an old remote client.
                    tearDown();
                }
            }


            private void createAndDoIOLoop()
            {
                if ( !checkOpenSocket() || !createIOStreams() )
                {
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
                catch ( IOException ex )
                {
                    Log.e( CLIENT_TAG, "Receiving loop io error: ", ex );
                }
                catch ( Exception ex )
                {
                    Log.e( CLIENT_TAG, "Receiving loop miscellaneous exception error: ", ex );
                }
            }


            // private void sleep( int ms )
            // {
            // try
            // {
            // wait( ms );
            // }
            // catch ( Exception ex )
            // {
            //
            // }
            // }
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
