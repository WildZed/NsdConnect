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


public class NsdHelper
{
    public static final String      TAG = "NsdHelper";
    public static final String      SERVICE_TYPE = "_http._tcp.";
    public static final String      SERVICE_NAME_PREFIX = "NsdComms";
   
    
    Context                         m_context;
    String                          m_serviceName;
    String                          m_localServiceName;
    String                          m_remoteServiceName;
    NsdManager                      m_nsdManager;
    NsdManager.ResolveListener      m_resolveListener;
    NsdManager.DiscoveryListener    m_discoveryListener;
    NsdManager.RegistrationListener m_registrationListener;
    NsdServiceInfo                  m_service;
    boolean                         m_serviceRegistered;
    boolean                         m_serviceDiscovery = false;


    public NsdHelper( Context context, String serviceName )
    {
        m_context = context;
        m_serviceName = SERVICE_NAME_PREFIX + " " + serviceName;
        m_localServiceName = null;
        m_remoteServiceName = null;
        m_nsdManager = (NsdManager) context.getSystemService( Context.NSD_SERVICE );
        m_serviceRegistered = false;
    }
    
    
    public boolean isServiceRegistered()
    {
        return m_serviceRegistered;
    }


    public void initializeNsd()
    {
        initializeRegistrationListener();
        initializeResolveListener();
        initializeDiscoveryListener();

        // m_nsdManager.init(m_context.getMainLooper(), this);
    }


    public void initializeRegistrationListener()
    {
        m_registrationListener = new NsdManager.RegistrationListener()
        {
            // @Override
            public void onServiceRegistered( NsdServiceInfo serviceInfo )
            {
                Log.d( TAG, "Service registered: " + serviceInfo );
                
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
                Log.d( TAG, "Service unregistered: " + serviceInfo );
                
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


    public void initializeResolveListener()
    {
        m_resolveListener = new NsdManager.ResolveListener()
        {
            // @Override
            public void onResolveFailed( NsdServiceInfo serviceInfo, int errorCode )
            {
                Log.e( TAG, "Resolve failed: " + errorCode );
            }


            // @Override
            public void onServiceResolved( NsdServiceInfo serviceInfo )
            {
                Log.d( TAG, "Resolve Succeeded: " + serviceInfo );

                if ( m_localServiceName != null && serviceInfo.getServiceName().equals( m_localServiceName ) )
                {
                    Log.d( TAG, "Same IP." );
                    return;
                }
                
                m_remoteServiceName = serviceInfo.getServiceName();
                m_service = serviceInfo;
            }
        };
    }


    public void initializeDiscoveryListener()
    {
        m_discoveryListener = new NsdManager.DiscoveryListener()
        {
            // @Override
            public void onDiscoveryStarted( String regType )
            {
                Log.d( TAG, "Service discovery started" );
            }


            // @Override
            public void onServiceFound( NsdServiceInfo service )
            {
                Log.d( TAG, "Service discovered: " + service );
                
                if ( ! service.getServiceType().equals( SERVICE_TYPE ) )
                {
                    Log.d( TAG, "Unknown Service Type: " + service.getServiceType() );
                }
                else if ( m_localServiceName != null && service.getServiceName().equals( m_localServiceName ) )
                {
                    Log.d( TAG, "Service on same machine: " + service );
                }
                else if ( service.getServiceName().contains( m_serviceName ) )
                {
                    Log.i( TAG, "Resolving service on remote machine: " + service );
                    
                    m_nsdManager.resolveService( service, m_resolveListener );
                }
            }


            // @Override
            public void onServiceLost( NsdServiceInfo service )
            {
                if ( m_service == service )
                {
                    Log.w( TAG, "Service lost: " + service );
                    
                    m_service = null;
                }
                else
                {
                    Log.i( TAG, "Service lost: " + service );
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


    public void registerService( int port )
    {
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
            Log.e( TAG, "Error stopping service discovery (may already be started): ", e );
            e.printStackTrace();
        }
    }


    public NsdServiceInfo getChosenServiceInfo()
    {
        return m_service;
    }


    public void tearDown()
    {
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
}
