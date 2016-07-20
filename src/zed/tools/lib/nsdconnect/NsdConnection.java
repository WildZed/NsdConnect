package zed.tools.lib.nsdconnect;

import android.content.Context;
import android.net.nsd.NsdServiceInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.util.Log;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.Enumeration;
import java.util.Hashtable;
//import java.util.Enumeration;
//import java.util.concurrent.ArrayBlockingQueue;
//import java.util.concurrent.BlockingQueue;


public class NsdConnection
{
    // Constants:
    private static final String TAG = NsdConnection.class.getSimpleName();

    // Member variables:
    private Handler                        m_updateHandler;
    String                                 m_serviceName;
    private NsdHelper                      m_nsdHelper;
    private CommsServer                    m_commsServer = null;
    private Hashtable<String, CommsClient> m_commsClients;
    private int                            m_localServerPort = 0;
    private boolean                        m_connected = false;


    public NsdConnection( Context context, String serviceName, Handler updateHandler )
    {
        m_updateHandler = updateHandler;
        m_serviceName = serviceName;
        m_commsClients = new Hashtable<String, CommsClient>();
        m_nsdHelper = new NsdHelper( context, serviceName, createNewNsdHelperHandler() );
    }
    
    
    private NsdHelperHandler createNewNsdHelperHandler()
    {
        return new NsdHelperHandler() {
            public void onNewService( NsdServiceInfo serviceInfo )
            {
                connectToServer( serviceInfo.getHost(), serviceInfo.getPort() );   
            }
            
            
            public void onLostService( NsdServiceInfo serviceInfo )
            {
            }
        };
    }
    
    
    public void tearDown()
    {
        Log.d( TAG, "Tearing down NsdConnection '" + m_serviceName + "'." );
        
        try
        {
            m_commsServer.tearDown();
            m_nsdHelper.tearDown();
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
    
    
    public void onPause()
    {      
        m_nsdHelper.stopDiscovery();
    }
   
    
    public void onResume()
    {      
        checkReconnectClients();
        m_nsdHelper.discoverServices();
    }
    
    
    public void startService()
    {
        if ( m_commsServer == null )
        {
            m_commsServer = new CommsServer();
        }
        
        // Register service.
//        if ( ! m_nsdHelper.isServiceRegistered() )
//        {
//            if ( m_localServerPort > -1 )
//            {
//                m_nsdHelper.registerService( m_localServerPort );
//            }
//            else
//            {
//                Log.e( TAG, "Server socket isn't bound." );
//            }
//        }
    }

    
    public void stopService()
    {
        m_commsServer.tearDown();
        m_commsServer = null;
    }

    
    public synchronized void connectToServer( InetAddress inetAddress, int inetPort )
    {
        String hostAddress = inetAddress.getHostAddress();
        CommsClient commsClient = m_commsClients.get( hostAddress );
        
        if ( commsClient == null )
        {
            commsClient = new CommsClient( inetAddress, inetPort );
        }
        else
        {
            commsClient.checkReconnect();
        }
    }


    public void sendMessage( String msg )
    {
        Enumeration<String> hostAddresses = m_commsClients.keys();
    
        while ( hostAddresses.hasMoreElements() )
        {
            String hostAddress = hostAddresses.nextElement();
            CommsClient commsClient = m_commsClients.get( hostAddress );
    
            commsClient.sendMessage( msg );
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


    private void tearDownCommsClients()
    {
        Enumeration<String> hostAddresses = m_commsClients.keys();
    
        while ( hostAddresses.hasMoreElements() )
        {
            String hostAddress = hostAddresses.nextElement();
            CommsClient commsClient = m_commsClients.get( hostAddress );
    
            commsClient.tearDown();
        }
    }


    private synchronized void connectToSocket( Socket socket )
    {
        String hostAddress = socket.getInetAddress().getHostAddress();
        CommsClient commsClient = m_commsClients.get( hostAddress );
        
        if ( commsClient != null )
        {
            // We've been given a new connected socket to the peer,
            // so get rid of the old.
            Log.w( TAG, "Replacing CommsClient for connection from: " + hostAddress + ":" + socket.getPort() );

            commsClient.tearDown();
        }
        
        commsClient = new CommsClient( socket );
    }
    
    
    private void checkReconnectClients()
    {
        Enumeration<String> hostAddresses = m_commsClients.keys();
        
        while ( hostAddresses.hasMoreElements() )
        {
            String hostAddress = hostAddresses.nextElement();
            CommsClient commsClient = m_commsClients.get( hostAddress );
    
            commsClient.checkReconnect();
        }        
    }


    private synchronized void updateMessages( String msg, boolean local )
    {
        if ( local )
        {
            msg = "me: " + msg;
        }
        else
        {
            msg = "them: " + msg;
        }
        
        Log.i( TAG, "Updating message: " + msg );

        Bundle messageBundle = new Bundle();
        messageBundle.putString( "msg", msg );

        Message message = new Message();
        message.setData( messageBundle );
        m_updateHandler.sendMessage( message );
    }

    
    private synchronized void notifyClientChange( boolean connected )
    {
        // Newly connected is easy.
        // Otherwise we have to check if there are any remaining connected CommsClients.
        if ( ! connected )
        {
            Enumeration<String> hostAddresses = m_commsClients.keys();
            
            while ( hostAddresses.hasMoreElements() )
            {
                String hostAddress = hostAddresses.nextElement();
                CommsClient commsClient = m_commsClients.get( hostAddress );
        
                if ( commsClient.isConnected() )
                {
                    connected = true;
                    break;
                }
            }
        }
        
        updateConnected( connected );
    }
    

    private void updateConnected( boolean connected )
    {
        if ( connected == m_connected )
        {
            return;
        }
        
        Log.i( TAG, "Updating client about connection: " + connected );
        
        m_connected = connected;
        
        Bundle  messageBundle = new Bundle();
        String  connStr = ( ( connected ) ? "1" : "0" );
        
        messageBundle.putString( "connected", connStr );

        Message message = new Message();
        
        message.setData( messageBundle );
        m_updateHandler.sendMessage( message );
    }


    // This is our local server to accept incoming connections from our peers.
    private class CommsServer
    {
        public final String SERVER_THREAD_NAME = "CommsServer:ServerThread";
        
        ServerSocket m_serverSocket = null;
        Thread       m_serverThread = null;


        public CommsServer()
        {
            m_serverThread = new Thread( new ServerThread(), SERVER_THREAD_NAME );
            m_serverThread.start();
        }


        public void tearDown()
        {
            Log.d( TAG, "Tearing down CommsServer for '" + m_serviceName + "'." );
            
            m_nsdHelper.unregisterService();
            m_serverThread.interrupt();
            
            try
            {
                m_serverSocket.close();
            }
            catch ( IOException ioe )
            {
                Log.e( TAG, "Error when closing server socket." );
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
                while ( ! Thread.currentThread().isInterrupted() )
                {
                    try
                    {
                        // Since discovery will happen via Nsd, we don't need to care which port is
                        // used. Just grab an available one and advertise it via Nsd.
                        m_serverSocket = new ServerSocket( m_localServerPort );
                        setLocalServerPort( m_serverSocket.getLocalPort() );
                        Log.d( TAG, "ServerSocket created, awaiting connection on port:" + m_localServerPort );
                        m_nsdHelper.registerService( m_localServerPort );
    
                        while ( ! Thread.currentThread().isInterrupted() )
                        {
                            Log.d( TAG, "Awaiting new connection on ServiceSocket..." );
                            Socket socket = m_serverSocket.accept();
                            Log.d( TAG, "Connected." );
    
                            // Create a new CommsClient using the socket we've just accepted.
                            connectToSocket( socket );
                        }
                    }
                    catch ( IOException e )
                    {
                        Log.w( TAG, "ServerSocket exception (can occur when closing ServerSocket): ", e );
                        e.printStackTrace();
                    }
                }
            }
        }
    }

    
    // This is the client to manage a connection to a peer, to send and receive.
    private class CommsClient
    {
        private final String CLIENT_TAG = "CommsClient";
//        public final String SENDING_THREAD_NAME = "CommsClient:SendingThread";
        public final String RECEIVING_THREAD_NAME = "CommsClient:ReceivingThread";
        
        private InetAddress  m_inetAddress = null;
        private int          m_inetPort = 0;
        private Socket       m_socket = null;
//        private Thread       m_sendingThread = null;
        private Thread       m_receivingThread = null;


        public CommsClient( InetAddress inetAddress, int inetPort )
        {
            m_inetAddress = inetAddress;
            m_inetPort = inetPort;
            
            String hostAddress = m_inetAddress.getHostAddress();
  
            Log.d( CLIENT_TAG, "Creating CommsClient for connection to: " + hostAddress + ":" + m_inetPort );
            
            m_commsClients.put( hostAddress, this );
            createReceivingThread();
        }
        
        
        public CommsClient( Socket socket )
        {
            m_socket = socket;
            m_inetAddress = m_socket.getInetAddress();
            m_inetPort = m_socket.getPort();
            
            String hostAddress = m_inetAddress.getHostAddress();
            
            Log.d( CLIENT_TAG, "Creating CommsClient for connection from: " + hostAddress + ":" + m_inetPort );
            
            m_commsClients.put( hostAddress, this );
            createReceivingThread();
        }
        
        
        public void tearDown()
        {
            String hostAddress = m_inetAddress.getHostAddress();
            
            Log.d( TAG, "Tearing down CommsClient for:" + hostAddress + ":" + m_inetPort );
            
            m_commsClients.remove( hostAddress );  
            closeSocket();
            interruptThreads();
            updateConnected( false );
        }
        
        
        public boolean isConnected()
        {
            return ( m_socket != null && m_socket.isConnected() );
        }
        
        
        public void checkReconnect()
        {
            if ( isConnected() )
            {
                return;
            }
            
            Log.d( TAG, "Reconnecting CommsClient for:" + m_inetAddress.getHostAddress() + ":" + m_inetPort );
           
            closeSocket();
            interruptThreads();
            createReceivingThread();
        }


        // Sending messages to our peer.
        public void sendMessage( String msg )
        {
            try
            {                
                if ( m_socket == null )
                {
                    Log.d( CLIENT_TAG, "Socket is null!" );
                }
                else if ( m_socket.getOutputStream() == null )
                {
                    Log.d( CLIENT_TAG, "Socket output stream is null!" );
                }
        
                PrintWriter out = new PrintWriter( new BufferedWriter( new OutputStreamWriter( m_socket.getOutputStream() ) ), true );
                out.println( msg );
                out.flush();
                updateMessages( msg, true );
            }
            catch ( UnknownHostException e )
            {
                Log.d( CLIENT_TAG, "Unknown Host!", e );
            }
            catch ( IOException e )
            {
                Log.d( CLIENT_TAG, "I/O Exception!", e );
            }
            catch ( Exception e )
            {
                Log.d( CLIENT_TAG, "Misc Exception!", e );
            }
            
            Log.d( CLIENT_TAG, "Client sent message: " + msg );
        }


        private void createReceivingThread()
        {
//            m_sendingThread = new Thread( new SendingThread(), SENDING_THREAD_NAME );
//            m_sendingThread.start();
            m_receivingThread = new Thread( new ReceivingThread(), RECEIVING_THREAD_NAME );
            m_receivingThread.start();
        }

        
        private void interruptThreads()
        {
            if ( m_receivingThread != null )
            {
                m_receivingThread.interrupt();
            }
            
//          if ( m_sendingThread != null )
//          {
//              m_sendingThread.interrupt();
//          }
            
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
                
                if ( ! m_socket.isClosed() )
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

        
        private void closeSocket()
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


        


        // I've no idea of the point of the sending thread, apart from to start the receiving thread.
//        class SendingThread implements Runnable
//        {
//            BlockingQueue<String> m_messageQueue;
//            private final int     QUEUE_CAPACITY = 10;
//
//
//            public SendingThread()
//            {
//                m_messageQueue = new ArrayBlockingQueue<String>( QUEUE_CAPACITY );
//            }
//
//
//            // @Override
//            public void run()
//            {
//                if ( ! checkOpenSocket() )
//                {
//                    return;
//                }
//
//                m_receivingThread = new Thread( new ReceivingThread(), RECEIVING_THREAD_NAME );
//                m_receivingThread.start();
//
//                while ( ! Thread.currentThread().isInterrupted() )
//                {
//                    try
//                    {
//                        String msg = m_messageQueue.take();
//                        
//                        Log.d( CLIENT_TAG, "Sending message from SendingThread: " + msg );
//                        
//                        sendMessage( msg );
//                    }
//                    catch ( InterruptedException ie )
//                    {
//                        Log.d( CLIENT_TAG, "Message sending loop interrupted, exiting." );
//                    }
//                }
//            }
//        }

        
        // The receiving thread asynchronously listens for and receives messages from a peer.
        class ReceivingThread implements Runnable
        {
            // @Override
            public void run()
            {
                if ( ! checkOpenSocket() )
                {
                    return;
                }
               
                try
                {
                    BufferedReader input = new BufferedReader( new InputStreamReader( m_socket.getInputStream() ) );

                    while ( ! Thread.currentThread().isInterrupted() )
                    {
                        String messageStr = input.readLine();

                        if ( messageStr != null )
                        {
                            Log.d( CLIENT_TAG, "Read from the stream: " + messageStr );
                            updateMessages( messageStr, false );
                        }
                        else
                        {
                            Log.d( CLIENT_TAG, "The nulls! The nulls!" );
                            break;
                        }
                    }

                    input.close();
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
