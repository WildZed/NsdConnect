package zed.tools.lib.nsdconnect;
/*
 * Copyright (C) 2012 The Android Open Source Project
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */



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
//import java.util.concurrent.ArrayBlockingQueue;
//import java.util.concurrent.BlockingQueue;


public class NsdConnection
{
    // Constants:
    private static final String TAG = "NsdConnection";

    // Member variables:
    private Handler     m_updateHandler;
    private CommsServer m_commsServer;
    private CommsClient m_commsClient;
    private int         m_localServerPort = 0;


    public NsdConnection( Handler handler )
    {
        m_updateHandler = handler;
        m_commsServer = new CommsServer();
    }


    public void tearDown()
    {
        m_commsServer.tearDown();
        
        if ( m_commsClient != null )
        {
            m_commsClient.tearDown();
        }
    }


    public void connectToServer( InetAddress inetAddress, int port )
    {
        if ( m_commsClient != null )
        {
            m_commsClient.tearDown();
        }
        
        m_commsClient = new CommsClient( inetAddress, port );
    }


    private void connectToSocket( Socket socket )
    {
//        if ( m_commsClient != null )
//        {
//            return;
//        }

        if ( m_commsClient != null )
        {
            m_commsClient.tearDown();
        }
        
        m_commsClient = new CommsClient( socket );
    }


    public void sendMessage( String msg )
    {
        if ( m_commsClient != null )
        {
            m_commsClient.sendMessage( msg );
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


    public synchronized void updateMessages( String msg, boolean local )
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


    public synchronized void updateConnected( boolean connected )
    {
        Log.i( TAG, "Updating client about connection: " + connected );

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
            m_serverThread.interrupt();
            
            try
            {
                m_serverSocket.close();
            }
            catch ( IOException ioe )
            {
                Log.e( TAG, "Error when closing server socket." );
            }
            
            // updateConnected( false );
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
                try
                {
                    // Since discovery will happen via Nsd, we don't need to care which port is
                    // used. Just grab an available one and advertise it via Nsd.
                    m_serverSocket = new ServerSocket( getLocalServerPort() );
                    setLocalServerPort( m_serverSocket.getLocalPort() );

                    while ( ! Thread.currentThread().isInterrupted() )
                    {
                        Log.d( TAG, "ServerSocket Created, awaiting connection" );
                        Socket socket = m_serverSocket.accept();
                        Log.d( TAG, "Connected." );

                        // Create a new CommsClient using the socket we've just accepted.
                        connectToSocket( socket );
                    }
                }
                catch ( IOException e )
                {
                    Log.e( TAG, "Error creating ServerSocket: ", e );
                    e.printStackTrace();
                }
                
                // updateConnected( false );
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
        private Thread       m_sendingThread = null;
        private Thread       m_receivingThread = null;


        public CommsClient( InetAddress inetAddress, int inetPort )
        {
            m_inetAddress = inetAddress;
            m_inetPort = inetPort;
            
            createReceivingThread();
        }
        
        
        public CommsClient( Socket socket )
        {
            m_socket = socket;
            m_inetAddress = m_socket.getInetAddress();
            m_inetPort = m_socket.getPort();

            createReceivingThread();
        }
        
        
        private void createReceivingThread()
        {
            Log.d( CLIENT_TAG, "Creating CommsClient for: " + m_inetAddress.getHostAddress() + ":" + m_inetPort );

//            m_sendingThread = new Thread( new SendingThread(), SENDING_THREAD_NAME );
//            m_sendingThread.start();
            m_receivingThread = new Thread( new ReceivingThread(), RECEIVING_THREAD_NAME );
            m_receivingThread.start();
        }

        
        private void interruptThreads()
        {
            if ( m_sendingThread != null )
            {
                m_sendingThread.interrupt();
            }
            
            if ( m_receivingThread != null )
            {
                m_receivingThread.interrupt();
            }
        }


        public void tearDown()
        {
            closeSocket();
            interruptThreads();
        }


        private synchronized void setSocket( Socket socket )
        {
            Log.d( TAG, "setSocket being called." );

            if ( socket == null )
            {
                Log.d( TAG, "Setting a null socket." );
            }
            
            if ( m_socket != null )
            {
                if ( m_socket.isConnected() )
                {
                    try
                    {
                        m_socket.close();
                    }
                    catch ( IOException e )
                    {
                        // TODO(alexlucas): Auto-generated catch block
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
                if ( m_socket == null || m_socket.isClosed() )
                {
                    setSocket( new Socket( m_inetAddress, m_inetPort ) );
                    Log.d( TAG, "Client-side socket initialized." );
                }
                else
                {
                    Log.d( TAG, "Socket already initialized. skipping!" );
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
                
                updateConnected( true );
               
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
                    Log.e( CLIENT_TAG, "Receiving loop misc exception error: ", e );
                }
               
                updateConnected( false );
                tearDown();
            }
        }
    }
}
