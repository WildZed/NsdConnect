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

import android.content.Context;
import android.net.nsd.NsdServiceInfo;
import android.net.nsd.NsdManager;
import android.util.Log;
import java.util.Hashtable;


// import java.util.Enumeration;

public class NsdHelper
{
    public static final String        TAG                 = "NsdHelper";
    public static final String        SERVICE_TYPE        = "_http._tcp.";
    public static final String        SERVICE_NAME_PREFIX = "NsdComms";

    Context                           m_context;
    String                            m_serviceName;
    String                            m_localServiceName;
    NsdManager                        m_nsdManager;
    NsdManager.RegistrationListener   m_registrationListener;
    NsdManager.DiscoveryListener      m_discoveryListener;
    NsdHelperHandler                  m_helperHandler;
    Hashtable<String, NsdServiceInfo> m_remoteServices;
    boolean                           m_serviceRegistered = false;
    boolean                           m_serviceDiscovery  = false;


    public NsdHelper( Context context, String serviceName, NsdHelperHandler helperHandler )
    {
        m_context = context;
        m_serviceName = SERVICE_NAME_PREFIX + " " + serviceName;
        m_helperHandler = helperHandler;
        m_localServiceName = null;
        m_nsdManager = (NsdManager) context.getSystemService( Context.NSD_SERVICE );
        m_serviceRegistered = false;
        m_serviceDiscovery = false;
        m_remoteServices = new Hashtable<String, NsdServiceInfo>();
        initialiseNsd();
    }


    public void tearDown()
    {
        Log.d( TAG, "Tearing down NsdHelper for '" + m_serviceName + "'." );

        unregisterService();
        stopDiscovery();
        m_remoteServices.clear();
        m_serviceName = null;
    }


    protected void finalize()
    {
        if ( m_serviceName != null )
        {
            tearDown();
        }
    }


    public String getServiceName()
    {
        return m_serviceName;
    }


    public void initialiseNsd()
    {
        initialiseRegistrationListener();
        initialiseDiscoveryListener();
        // initialiseResolveListener();

        // m_nsdManager.init(m_context.getMainLooper(), this);
    }


    public void registerService( int port )
    {
        // if ( isServiceRegistered() )
        // {
        // Log.w( TAG, "Service '" + m_serviceName + "' is already registered." );
        //
        // return;
        // }

        Log.i( TAG, "Registering service '" + m_serviceName + "'." );

        NsdServiceInfo serviceInfo = new NsdServiceInfo();
        serviceInfo.setPort( port );
        serviceInfo.setServiceName( m_serviceName );
        serviceInfo.setServiceType( SERVICE_TYPE );

        try
        {
            m_nsdManager.registerService( serviceInfo, NsdManager.PROTOCOL_DNS_SD, m_registrationListener );
        }
        catch ( Exception e )
        {
            Log.e( TAG, "Error registering service (may already be registered): ", e );
            e.printStackTrace();
        }
    }


    public void unregisterService()
    {
        m_serviceRegistered = false;

        try
        {
            m_nsdManager.unregisterService( m_registrationListener );
        }
        catch ( Exception e )
        {
            Log.e( TAG, "Error unregistering service (may not have been registered): ", e );
            e.printStackTrace();
        }
    }


    public boolean isServiceRegistered()
    {
        return m_serviceRegistered;
    }


    public void discoverServices()
    {
        if ( m_serviceDiscovery )
        {
            Log.w( TAG, "Service discovery already started!" );
            return;
        }

        try
        {
            m_serviceDiscovery = true;
            m_nsdManager.discoverServices( SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, m_discoveryListener );
        }
        catch ( IllegalArgumentException e )
        {
            m_serviceDiscovery = false;
            Log.e( TAG, "Error starting service discovery (may already be started): ", e );
            e.printStackTrace();
        }
        catch ( Exception e )
        {
            m_serviceDiscovery = false;
            Log.e( TAG, "Error starting service discovery (may already be started): ", e );
            e.printStackTrace();
        }
    }


    public void stopDiscovery()
    {
        m_serviceDiscovery = false;

        try
        {
            m_nsdManager.stopServiceDiscovery( m_discoveryListener );
        }
        catch ( Exception e )
        {
            Log.e( TAG, "Error stopping service discovery (may already be stopped): ", e );
            e.printStackTrace();
        }
    }


    public Hashtable<String, NsdServiceInfo> getAllServiceInfos()
    {
        return m_remoteServices;
    }


    private synchronized void addNewServiceInfo( NsdServiceInfo serviceInfo )
    {
        String serviceName = serviceInfo.getServiceName();

        if ( m_localServiceName != null && serviceName.equals( m_localServiceName ) )
        {
            Log.d( TAG, "Same IP." );
            return;
        }

        m_remoteServices.put( serviceName, serviceInfo );
        m_helperHandler.onNewService( serviceInfo );
    }


    private void initialiseRegistrationListener()
    {
        m_registrationListener = new NsdManager.RegistrationListener()
        {
            // @Override
            public void onServiceRegistered( NsdServiceInfo serviceInfo )
            {
                Log.i( TAG, "Service registered: " + serviceInfo );

                m_localServiceName = serviceInfo.getServiceName();
                m_serviceRegistered = true;
            }


            // @Override
            public void onRegistrationFailed( NsdServiceInfo serviceInfo, int errorCode )
            {
                Log.e( TAG, "Service registration failed: " + serviceInfo + " error code: " + errorCode );

                m_serviceRegistered = false;
            }


            // @Override
            public void onServiceUnregistered( NsdServiceInfo serviceInfo )
            {
                Log.i( TAG, "Service unregistered: " + serviceInfo );

                m_serviceRegistered = false;
            }


            // @Override
            public void onUnregistrationFailed( NsdServiceInfo serviceInfo, int errorCode )
            {
                Log.e( TAG, "Service unregistration failed: " + serviceInfo + " error code: " + errorCode );

                m_serviceRegistered = false;
            }

        };
    }


    private void initialiseDiscoveryListener()
    {
        m_discoveryListener = new NsdManager.DiscoveryListener()
        {
            // @Override
            public void onDiscoveryStarted( String regType )
            {
                Log.i( TAG, "Service discovery started" );
            }


            // @Override
            public void onServiceFound( NsdServiceInfo serviceInfo )
            {
                Log.d( TAG, "Service discovered: " + serviceInfo );

                String serviceName = serviceInfo.getServiceName();

                if ( !serviceInfo.getServiceType().equals( SERVICE_TYPE ) )
                {
                    Log.d( TAG, "Unknown Service Type: " + serviceInfo.getServiceType() );
                }
                else if ( m_localServiceName != null && serviceName.equals( m_localServiceName ) )
                {
                    Log.d( TAG, "Service on same machine: " + serviceInfo );
                }
                else if ( serviceName.contains( m_serviceName ) )
                {
                    Log.i( TAG, "Resolving service on remote machine: " + serviceInfo );

                    m_nsdManager.resolveService( serviceInfo, createResolveListener() );
                }
            }


            // @Override
            public void onServiceLost( NsdServiceInfo serviceInfo )
            {
                NsdServiceInfo removedService = m_remoteServices.remove( serviceInfo.getServiceName() );

                if ( removedService != null )
                {
                    Log.w( TAG, "Remote service lost: " + serviceInfo );
                }
                else
                {
                    Log.d( TAG, "Other service lost: " + serviceInfo );
                }
            }


            // @Override
            public void onDiscoveryStopped( String serviceType )
            {
                Log.i( TAG, "Discovery stopped: " + serviceType );
            }


            // @Override
            public void onStartDiscoveryFailed( String serviceType, int errorCode )
            {
                Log.e( TAG, "Discovery failed: Error code:" + errorCode );

                m_nsdManager.stopServiceDiscovery( this );
            }


            // @Override
            public void onStopDiscoveryFailed( String serviceType, int errorCode )
            {
                Log.e( TAG, "Discovery failed: Error code:" + errorCode );

                m_nsdManager.stopServiceDiscovery( this );
            }
        };
    }


    private NsdManager.ResolveListener createResolveListener()
    {
        return new NsdManager.ResolveListener()
        {
            // @Override
            public void onResolveFailed( NsdServiceInfo serviceInfo, int errorCode )
            {
                Log.e( TAG, "Resolve failed: " + errorCode );
            }


            // @Override
            public void onServiceResolved( NsdServiceInfo serviceInfo )
            {
                Log.d( TAG, "Resolve succeeded: " + serviceInfo );

                addNewServiceInfo( serviceInfo );
            }
        };
    }

}
