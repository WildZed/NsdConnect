package zed.tools.lib.nsdconnect;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
// import android.content.res.Resources;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.util.Log;
import android.widget.Toast;


public class NsdServiceConnection implements ServiceConnection
{
    private final String TAG              = NsdServiceConnection.class.getSimpleName();

    private NsdService   m_nsdService;
    private boolean      m_isLocalService = true;
    private boolean      m_isBound        = false;
    private Messenger    m_clientMessenger;
    private Messenger    m_serviceMessenger;
    private Context      m_context;
    private String       m_serviceName;


    // private static class IncomingHandler extends Handler
    // {
    // @Override
    // public void handleMessage( Message msg )
    // {
    // switch ( msg.what )
    // {
    // case NsdService.MSG_TEXT:
    // break;
    //
    // case NsdService.MSG_OBJECT:
    // break;
    //
    // default:
    // super.handleMessage( msg );
    // break;
    // }
    // }
    // }

    public NsdServiceConnection( boolean isLocalService, Context context, String serviceName, Handler clientHandler )
    {
        m_isLocalService = isLocalService;
        m_context = context;
        m_serviceName = serviceName;
        m_clientMessenger = new Messenger( clientHandler );
        
        startService();
    }


    public void onServiceConnected( ComponentName className, IBinder nsdServiceBinder )
    {
        Message msg = Message.obtain( null, NsdService.MSG_REGISTER_CLIENT );
        Bundle bundle = new Bundle();

        bundle.putString( NsdService.SERVICE_NAME, m_serviceName );
        msg.setData( bundle );

        if ( m_isLocalService )
        {
            // Local process service access.
            m_nsdService = ( (NsdService.LocalBinder) nsdServiceBinder ).getService();
            sendMessageToLocalService( msg );
        }
        else
        {
            // Remote process service communication.
            // This is called when the connection with the service has been
            // established, giving us the service object we can use to
            // interact with the service. Because we have bound to a explicit
            // service that we know is running in our own process, we can
            // cast its IBinder to a concrete class and directly access it.
            m_serviceMessenger = new Messenger( nsdServiceBinder );
            sendMessageToRemoteService( msg );
        }

        Log.d( TAG, className + " bound to service '" + m_serviceName + "'." );

        toast( R.string.nsd_service_connected );
    }


    public void onServiceDisconnected( ComponentName className )
    {
        // This is called when the connection with the service has been
        // unexpectedly disconnected -- that is, its process crashed.
        // Because it is running in our same process, we should never
        // see this happen.
        // m_nsdService.tearDown();
        m_serviceMessenger = null;
        m_nsdService = null;

        Log.d( TAG, className + " disconnected from service '" + m_serviceName + "'." );
        
        toast( R.string.nsd_service_disconnected );
    }


    public void bindService()
    {
        // Establish a connection with the service. We use an explicit
        // class name because we want a specific service implementation that
        // we know will be running in our own process (and thus won't be
        // supporting component replacement by other applications).
        m_context.bindService( new Intent( m_context, NsdService.class ), this, Context.BIND_IMPORTANT );
        m_isBound = true;
    }


    public void unbindService()
    {
        if ( m_isBound )
        {
            // Detach our existing connection.
            m_context.unbindService( this );
            m_isBound = false;
        }
    }


    public void sendMessage( Message msg )
    {
        if ( !m_isBound )
        {
            Log.w( TAG, "Attempt to send message to unbound service failed: " + msg.toString() );
            return;
        }

        if ( m_isLocalService )
        {
            sendMessageToLocalService( msg );
        }
        else
        {
            sendMessageToRemoteService( msg );
        }
    }


    public void refresh()
    {
        sendMessageToRemoteService( Message.obtain( null, NsdService.MSG_REFRESH ) );
    }


    public void pause()
    {
        sendMessageToRemoteService( Message.obtain( null, NsdService.MSG_PAUSE ) );
    }


    public void resume()
    {
        sendMessageToRemoteService( Message.obtain( null, NsdService.MSG_RESUME ) );
    }
    
    
    // This starts the service if not already running.
    private void startService()
    {
        Intent startServiceIntent = new Intent( m_context, NsdService.class );
        
        startServiceIntent.setAction( NsdService.ACTION_START_SERVICE );
        // This sets the name of the service, but may be changed later by onServiceConnected().
        startServiceIntent.putExtra( NsdService.CLIENT_PACKAGE, m_context.getPackageName() );
        startServiceIntent.putExtra( NsdService.CLIENT_CLASS, m_context.getClass().getSimpleName() );
        startServiceIntent.putExtra( NsdService.SERVICE_NAME, m_serviceName );
        m_context.startService( startServiceIntent );
        
    }


    private void sendMessageToLocalService( Message msg )
    {
        if ( m_nsdService == null )
        {
            Log.w( TAG, "Attempt to send message to unconnected local service failed: " + msg.toString() );
            return;
        }

        msg.replyTo = m_clientMessenger;
        m_nsdService.sendMessage( msg );
    }


    private void sendMessageToRemoteService( Message msg )
    {
        if ( m_serviceMessenger == null )
        {
            Log.w( TAG, "Attempt to send message to unconnected remote service failed: " + msg.toString() );
            return;
        }

        try
        {
            msg.replyTo = m_clientMessenger;
            m_serviceMessenger.send( msg );
        }
        catch ( RemoteException ex )
        {
            Log.e( TAG, "Remote exception, sending to NSD Service: " + ex.toString() );
        }
        catch ( Exception ex )
        {
            Log.e( TAG, "Unexpected exception, sending to NSD Service: " + ex.toString() );
        }
    }
    
    
    private void toast( int strId )
    {
        String toastStr = m_context.getString( strId );
        
        Toast.makeText( m_context, toastStr, Toast.LENGTH_SHORT ).show();
    }

}

// private String getResourceString( int resId )
// {
// String str = m_resources.getString( resId );
//
// return str;
// }
