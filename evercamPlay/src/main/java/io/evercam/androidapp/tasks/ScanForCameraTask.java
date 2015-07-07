package io.evercam.androidapp.tasks;

import android.os.AsyncTask;
import android.util.Log;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Date;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import io.evercam.Vendor;
import io.evercam.androidapp.ScanActivity;
import io.evercam.androidapp.dto.AppData;
import io.evercam.androidapp.feedback.ScanFeedbackItem;
import io.evercam.androidapp.utils.Commons;
import io.evercam.androidapp.utils.NetInfo;
import io.evercam.network.Constants;
import io.evercam.network.EvercamDiscover;
import io.evercam.network.discovery.DiscoveredCamera;
import io.evercam.network.discovery.GatewayDevice;
import io.evercam.network.discovery.IpScan;
import io.evercam.network.discovery.MacAddress;
import io.evercam.network.discovery.NatMapEntry;
import io.evercam.network.discovery.NetworkInfo;
import io.evercam.network.discovery.PortScan;
import io.evercam.network.discovery.ScanRange;
import io.evercam.network.discovery.ScanResult;
import io.evercam.network.discovery.UpnpDevice;
import io.evercam.network.discovery.UpnpDiscovery;
import io.evercam.network.discovery.UpnpResult;
import io.evercam.network.onvif.OnvifDiscovery;
import io.evercam.network.query.EvercamQuery;

public class ScanForCameraTask extends AsyncTask<Void, DiscoveredCamera, ArrayList<DiscoveredCamera>>
{
    private final String TAG = "ScanForCameraTask";

    private WeakReference<ScanActivity> scanActivityReference;
    private NetInfo netInfo;
    private Date startTime;
    public ExecutorService pool;
    public static ArrayList<DiscoveredCamera> cameraList;
    public ArrayList<UpnpDevice> upnpDeviceList;
    private boolean upnpDone = false;
    private boolean natDone = false;
    private boolean onvifDone = false;

    //Check if single IP scan and port scan is completed or not by comparing the start and end count
    private int singleIpStartedCount = 0;
    private int singleIpEndedCount = 0;

    private String externalIp = "";
    private float scanPercentage = 0;
    private int totalDevices = 255;
    //ONVIF,SSDP and NAT discovery take 9 percents each. The rest is allocated to IP scan
    private final int PER__DISCOVERY_METHOD_PERCENT = 9;

    public ScanForCameraTask(ScanActivity scanActivity)
    {
        this.scanActivityReference = new WeakReference<>(scanActivity);
        netInfo = new NetInfo(scanActivity);
        pool = Executors.newFixedThreadPool(EvercamDiscover.DEFAULT_FIXED_POOL);
        cameraList = new ArrayList<>();
        upnpDeviceList = new ArrayList<>();
    }

    @Override
    protected void onPreExecute()
    {
        getScanActivity().onScanningStarted();
    }

    @Override
    protected ArrayList<DiscoveredCamera> doInBackground(Void... params)
    {
        startTime = new Date();
        try
        {
            final ScanRange scanRange = new ScanRange(netInfo.getGatewayIp(), netInfo.getNetmaskIp());
            totalDevices = scanRange.size();

            externalIp = NetworkInfo.getExternalIP();

            if(!pool.isShutdown() && ! isCancelled())
            {
                pool.execute(new OnvifRunnable());
                pool.execute(new UpnpRunnable());
                pool.execute(new NatRunnable(netInfo.getGatewayIp()));
            }

            IpScan ipScan = new IpScan(new ScanResult(){
                @Override
                public void onActiveIp(String ip)
                {
                    if(!pool.isShutdown() && !isCancelled())
                    {
                        pool.execute(new IpScanRunnable(ip));
                    }
                }

                @Override
                public void onIpScanned(String ip)
                {
                    scanPercentage += getPerDevicePercent();

                    updatePercentageOnActivity(scanPercentage);
                }
            });
            ipScan.scanAll(scanRange);
        }
        catch(Exception e)
        {
            Log.e(TAG, e.getLocalizedMessage());
            e.printStackTrace();
        }

        int loopCount = 0;
        while(!onvifDone && !upnpDone || ! natDone || singleIpStartedCount != singleIpEndedCount)
        {
            loopCount++;

            if(loopCount > 20) break; //Wait for maximum 10 secs

            if(isCancelled()) break;

            try
            {
                Thread.sleep(500);
            }
            catch(InterruptedException e)
            {
                e.printStackTrace();
            }
        }
        return cameraList;
    }

    @Override
    protected void onProgressUpdate(DiscoveredCamera... discoveredCameras)
    {
        if(getScanActivity() != null)
        {
            getScanActivity().addNewCameraToResultList(discoveredCameras[0]);
        }
    }

    @Override
    protected void onPostExecute(ArrayList<DiscoveredCamera> cameraList)
    {
        if(getScanActivity() != null)
        {
            getScanActivity().showScanResults(cameraList);
            getScanActivity().onScanningFinished();
        }

        pool.shutdown();

        Float scanningTime = Commons.calculateTimeDifferenceFrom(startTime);
        Log.d(TAG, "Scanning time: " + scanningTime);

        String username = "";
        if(AppData.defaultUser != null)
        {
            username = AppData.defaultUser.getUsername();
        }
        new ScanFeedbackItem(getScanActivity(), username, scanningTime, cameraList).sendToKeenIo();
    }
    
    private float getPerDevicePercent()
    {
        return (float)(100 - PER__DISCOVERY_METHOD_PERCENT*3)/totalDevices;
    }
    
    private ScanActivity getScanActivity()
    {
        return scanActivityReference.get();
    }

    private void updatePercentageOnActivity(Float percentage)
    {
        if(getScanActivity() != null)
        {
            if(percentage != null)
            {
                if(!isCancelled() && getStatus() != Status.FINISHED)
                {
                    getScanActivity().updateScanPercentage(percentage);
                }
            }
            else
            {
                getScanActivity().updateScanPercentage(percentage);
            }
        }
    }

    private class OnvifRunnable implements Runnable
    {
        @Override
        public void run()
        {
            new OnvifDiscovery(){
                @Override
                public void onActiveOnvifDevice(DiscoveredCamera discoveredCamera)
                {
                    discoveredCamera.setExternalIp(externalIp);
                    publishProgress(discoveredCamera);
                }
            }.probe();

            scanPercentage += PER__DISCOVERY_METHOD_PERCENT;
            updatePercentageOnActivity(scanPercentage);
            onvifDone = true;
        }
    }

    private class IpScanRunnable implements Runnable
    {
        private String ip;

        public IpScanRunnable(String ip)
        {
            this.ip = ip;
            singleIpStartedCount++;
        }

        @Override
        public void run()
        {
            try
            {
                String macAddress = MacAddress.getByIpAndroid(ip);
                if (!macAddress.equals(Constants.EMPTY_MAC))
                {
                    Vendor vendor = EvercamQuery.getCameraVendorByMac(macAddress);
                    if (vendor != null)
                    {
                        String vendorId = vendor.getId();
                        if (!vendorId.isEmpty())
                        {
                            // Then fill details discovered from IP scan
                            DiscoveredCamera camera = new DiscoveredCamera(ip);
                            camera.setMAC(macAddress);
                            camera.setVendor(vendorId);
                            camera.setExternalIp(externalIp);

                            // Start port scan
                            PortScan portScan = new PortScan(null);
                            portScan.start(ip);
                            ArrayList<Integer> activePortList = portScan.getActivePorts();

                            if(activePortList.size() > 0)
                            {
                                // Add active ports to camera object
                                for (Integer port : activePortList)
                                {
                                    camera = PortScan.mergePort(camera, port);
                                }

                                //Iterate UPnP device list and publish the UPnP details if matches
                                if(upnpDeviceList.size() > 0)
                                {
                                    for(UpnpDevice upnpDevice : upnpDeviceList)
                                    {
                                        String ipFromUpnp = upnpDevice.getIp();
                                        if (ipFromUpnp != null && !ipFromUpnp.isEmpty())
                                        {
                                            if(ipFromUpnp.equals(camera.getIP()))
                                            {
                                                EvercamDiscover.mergeSingleUpnpDeviceToCamera(upnpDevice, camera);
                                                break;
                                            }
                                        }
                                    }
                                }
                                publishProgress(camera);
                            }
                        }
                    }
                }
            }
            catch (Exception e)
            {
                e.printStackTrace();
            }

            singleIpEndedCount ++;
        }
    }

    private class UpnpRunnable implements Runnable
    {
        @Override
        public void run()
        {
            try
            {
                UpnpDiscovery upnpDiscovery = new UpnpDiscovery(new UpnpResult(){

                    @Override
                    public void onUpnpDeviceFound(UpnpDevice upnpDevice)
                    {
                        Log.d(TAG, "UPnP device found: " + upnpDevice.toString());
                        upnpDeviceList.add(upnpDevice);
                        // If IP address matches
                        String ipFromUpnp = upnpDevice.getIp();
                        if (ipFromUpnp != null && !ipFromUpnp.isEmpty())
                        {
                            for(DiscoveredCamera discoveredCamera : cameraList)
                            {
                                if(discoveredCamera.getIP().equals(upnpDevice.getIp()))
                                {
                                    DiscoveredCamera publishCamera = new DiscoveredCamera(discoveredCamera.getIP());
                                    EvercamDiscover.mergeSingleUpnpDeviceToCamera(upnpDevice, publishCamera);
                                    publishProgress(publishCamera);
                                    break;
                                }
                            }
                        }
                    }
                });
                upnpDiscovery.discoverAll();
            }
            catch (Exception e)
            {
                e.printStackTrace();
            }
            upnpDone = true;
            scanPercentage += PER__DISCOVERY_METHOD_PERCENT;
            updatePercentageOnActivity(scanPercentage);
        }
    }

    private class NatRunnable implements Runnable
    {
        private String routerIp;

        public NatRunnable(String routerIp)
        {
            this.routerIp = routerIp;
        }

        @Override
        public void run()
        {
            try
            {
                GatewayDevice gatewayDevice = new GatewayDevice(routerIp);
                ArrayList<NatMapEntry> mapEntries = gatewayDevice.getNatTableArray(); //NAT Table

                if(mapEntries.size() > 0)
                {
                    for(NatMapEntry mapEntry : mapEntries)
                    {
                        String natIp = mapEntry.getIpAddress();

                        for(DiscoveredCamera discoveredCamera : cameraList)
                        {
                            if(discoveredCamera.getIP().equals(natIp))
                            {
                                DiscoveredCamera publishCamera = EvercamDiscover.mergeNatEntryToCameraIfMatches(discoveredCamera, mapEntry);

                                publishProgress(publishCamera);

                                break; //break the inner loop
                            }
                        }
                    }
                }
            }
            catch (Exception e)
            {
                e.printStackTrace();
            }
            natDone = true;
            scanPercentage += PER__DISCOVERY_METHOD_PERCENT;
            updatePercentageOnActivity(scanPercentage);
        }
    }
}
