package com.gulpar567.usbinspector;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Build;
import java.nio.charset.StandardCharsets;

public class UsbController {
    public static final String ACTION_USB_PERMISSION = "com.gulpar567.usbinspector.USB_PERMISSION";
    private final UsbManager usbManager;
    private final Context context;
    private UsbDeviceConnection connection;
    private UsbInterface activeInterface;
    private UsbEndpoint endpointIn;
    private UsbEndpoint endpointOut;

    public UsbController(Context context) {
        this.context = context;
        this.usbManager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
    }

    public UsbDevice findModem() {
        if (usbManager == null) return null;
        for (UsbDevice device : usbManager.getDeviceList().values()) {
            if (device.getVendorId() == 0x12D1) { // Huawei Vendor ID
                return device;
            }
        }
        return null;
    }

    public boolean hasPermission(UsbDevice device) {
        return usbManager != null && usbManager.hasPermission(device);
    }

    public void requestPermission(UsbDevice device) {
        int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ? PendingIntent.FLAG_MUTABLE : 0;
        PendingIntent permissionIntent = PendingIntent.getBroadcast(
            context, 0, new Intent(ACTION_USB_PERMISSION), flags
        );
        usbManager.requestPermission(device, permissionIntent);
    }

    public synchronized boolean connect(UsbDevice device) {
        if (!hasPermission(device)) return false;

        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface iface = device.getInterface(i);
            UsbEndpoint epIn = null;
            UsbEndpoint epOut = null;

            for (int j = 0; j < iface.getEndpointCount(); j++) {
                UsbEndpoint ep = iface.getEndpoint(j);
                if (ep.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                    if (ep.getDirection() == UsbConstants.USB_DIR_IN) {
                        epIn = ep;
                    } else {
                        epOut = ep;
                    }
                }
            }

            if (epIn != null && epOut != null) {
                activeInterface = iface;
                endpointIn = epIn;
                endpointOut = epOut;
                break;
            }
        }

        if (activeInterface == null) return false;

        connection = usbManager.openDevice(device);
        if (connection == null) {
            return false;
        }

        if (!connection.claimInterface(activeInterface, true)) {
            connection.close();
            connection = null;
            return false;
        }

        return true;
    }

    public synchronized String sendAtCommand(String command) {
        if (connection == null || endpointOut == null || endpointIn == null) {
            return "ERROR: Connection not established";
        }

        byte[] request = (command + "\r").getBytes(StandardCharsets.UTF_8);
        int sendResult = connection.bulkTransfer(endpointOut, request, request.length, 1000);

        if (sendResult < 0) return "ERROR: Failed to write to USB endpoint";

        StringBuilder response = new StringBuilder();
        byte[] readBuffer = new byte[1024];
        long startTime = System.currentTimeMillis();

        while (System.currentTimeMillis() - startTime < 2000) {
            if (connection == null) break;
            int bytesRead = connection.bulkTransfer(endpointIn, readBuffer, readBuffer.length, 500);
            if (bytesRead > 0) {
                String chunk = new String(readBuffer, 0, bytesRead, StandardCharsets.UTF_8);
                response.append(chunk);
                if (response.toString().contains("OK") || response.toString().contains("ERROR")) {
                    break;
                }
            }
        }

        return response.length() > 0 ? response.toString().trim() : "NO RESPONSE";
    }

    public synchronized void disconnect() {
        if (connection != null) {
            if (activeInterface != null) {
                connection.releaseInterface(activeInterface);
                activeInterface = null;
            }
            connection.close();
            connection = null;
        }
        endpointIn = null;
        endpointOut = null;
    }
}
