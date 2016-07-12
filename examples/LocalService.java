package zed.tools.lib.nsdconnect;

import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.util.Log;




public class LocalService extends Service
{
    private NotificationManager m_notificationManager;

    // Unique Identification Number for the Notification.
    // We use it on Notification start, and to cancel it.
//    private final int           LOCAL_SERVICE_STARTED = 1;                // R.string.local_service_started;
//    private final int           LOCAL_SERVICE_STOPPED = 0;                // R.string.local_service_stopped;
    private final int           NOTIFICATION          = 2;                // R.string.local_service_started;

    // This is the object that receives interactions from clients. See
    // RemoteService for a more complete example.
    private final IBinder       m_binder              = new LocalBinder();


    /**
     * Class for clients to access. Because we know this service always
     * runs in the same process as its clients, we don't need to deal with
     * IPC.
     */
    public class LocalBinder extends Binder
    {
        LocalService getService()
        {
            return LocalService.this;
        }
    }


    @Override
    public void onCreate()
    {
        m_notificationManager = (NotificationManager) getSystemService( NOTIFICATION_SERVICE );

        // Display a notification about us starting. We put an icon in the status bar.
        showNotification();
    }


    @Override
    public int onStartCommand( Intent intent, int flags, int startId )
    {
        Log.i( "LocalService", "Received start id " + startId + ": " + intent );

        return START_NOT_STICKY;
    }


    @Override
    public void onDestroy()
    {
        // Cancel the persistent notification.
        m_notificationManager.cancel( NOTIFICATION );

        // Tell the user we stopped.
//        Toast.makeText( this, LOCAL_SERVICE_STOPPED, Toast.LENGTH_SHORT ).show();
    }


    @Override
    public IBinder onBind( Intent intent )
    {
        return m_binder;
    }


    /**
     * Show a notification while this service is running.
     */
    private void showNotification()
    {
        // In this sample, we'll use the same text for the ticker and the expanded notification
        // CharSequence text = getText( LOCAL_SERVICE_STARTED );

        // The PendingIntent to launch our activity if the user selects this notification
        // PendingIntent contentIntent = PendingIntent.getActivity( this, 0, new Intent( this, LocalServiceActivities.Controller.class ), 0
        // );

        // Set the info for the views that show in the notification panel.
        // Notification notification = new Notification.Builder( this ).setSmallIcon( R.drawable.stat_sample ) // the status icon
        // .setTicker( text ) // the status text
        // .setWhen( System.currentTimeMillis() ) // the time stamp
        // .setContentTitle( getText( R.string.local_service_label ) ) // the label of the entry
        // .setContentText( text ) // the contents of the entry
        // .setContentIntent( contentIntent ) // The intent to send when the entry is clicked
        // .build();

        // Send the notification.
        // m_notificationManager.notify( NOTIFICATION, notification );
    }
}
