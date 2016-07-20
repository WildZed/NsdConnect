package zed.tools.lib.nsdconnect;

import android.net.nsd.NsdServiceInfo;




public interface NsdHelperHandler
{
    public void onNewService( NsdServiceInfo serviceInfo );
    
    public void onLostService( NsdServiceInfo serviceInfo );
}
