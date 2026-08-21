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

    public synchronized String scanUsbDeviceTopology(UsbDevice device) {
        if (!hasPermission(device)) return "ERROR: USB permission not granted";

        UsbDeviceConnection connection = usbManager.openDevice(device);
        if (connection == null) return "ERROR: Failed to open USB connection";

        StringBuilder sb = new StringBuilder();
        sb.append("=== USB TOPOLOGY DIAGNOSTICS ===\n");
        sb.append("Device Name: ").append(device.getDeviceName()).append("\n");
        sb.append("Vendor ID: 0x").append(Integer.toHexString(device.getVendorId()).toUpperCase()).append("\n");
        sb.append("Product ID: 0x").append(Integer.toHexString(device.getProductId()).toUpperCase()).append("\n");
        sb.append("Interface Count: ").append(device.getInterfaceCount()).append("\n\n");

        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface iface = device.getInterface(i);
            sb.append("------------------------------\n");
            sb.append("INTERFACE #").append(i).append(" (ID: ").append(iface.getId()).append(")\n");
            sb.append("  Class: ").append(iface.getInterfaceClass())
              .append(" | Subclass: ").append(iface.getInterfaceSubclass())
              .append(" | Protocol: ").append(iface.getInterfaceProtocol()).append("\n");
            sb.append("  Endpoints Count: ").append(iface.getEndpointCount()).append("\n");

            UsbEndpoint epIn = null;
            UsbEndpoint epOut = null;

            for (int j = 0; j < iface.getEndpointCount(); j++) {
                UsbEndpoint ep = iface.getEndpoint(j);
                String dir = (ep.getDirection() == UsbConstants.USB_DIR_IN) ? "IN" : "OUT";
                String typeStr = getEndpointTypeString(ep.getType());

                sb.append("    EP #").append(j)
                  .append(" | Dir: ").append(dir)
                  .append(" | Type: ").append(typeStr)
                  .append(" | MaxSize: ").append(ep.getMaxPacketSize())
                  .append(" | Addr: 0x").append(Integer.toHexString(ep.getAddress()).toUpperCase())
                  .append("\n");

                if (ep.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                    if (ep.getDirection() == UsbConstants.USB_DIR_IN) {
                        epIn = ep;
                    } else {
                        epOut = ep;
                    }
                }
            }

            if (epIn != null && epOut != null) {
                if (connection.claimInterface(iface, true)) {
                    String atResult = testAtCommandOnEndpoints(connection, epOut, epIn);
                    sb.append("  -> [AT TEST RESULT]: ").append(atResult).append("\n");
                    connection.releaseInterface(iface);
                } else {
                    sb.append("  -> [AT TEST RESULT]: Claim Interface Failed\n");
                }
            } else {
                sb.append("  -> [AT TEST RESULT]: N/A (No BULK IN/OUT Pair)\n");
            }
        }

        connection.close();
        return sb.toString();
    }

    private String testAtCommandOnEndpoints(UsbDeviceConnection connection, UsbEndpoint epOut, UsbEndpoint epIn) {
        try {
            // Qoldiq buferni tozalash
            byte[] dummyBuffer = new byte[1024];
            connection.bulkTransfer(epIn, dummyBuffer, dummyBuffer.length, 100);

            byte[] request = "AT\r".getBytes(StandardCharsets.UTF_8);
            int sendResult = connection.bulkTransfer(epOut, request, request.length, 1000);

            if (sendResult < 0) return "Failed to write AT request";

            StringBuilder response = new StringBuilder();
            byte[] readBuffer = new byte[1024];
            long startTime = System.currentTimeMillis();

            while (System.currentTimeMillis() - startTime < 1000) {
                int bytesRead = connection.bulkTransfer(epIn, readBuffer, readBuffer.length, 300);
                if (bytesRead > 0) {
                    String chunk = new String(readBuffer, 0, bytesRead, StandardCharsets.UTF_8);
                    response.append(chunk);
                    if (response.toString().contains("OK") || response.toString().contains("ERROR")) {
                        break;
                    }
                }
            }

            String clean = response.toString().trim().replace("\r", " ").replace("\n", " ");
            return clean.isEmpty() ? "NO RESPONSE" : clean;
        } catch (Exception e) {
            return "ERROR: " + e.getMessage();
        }
    }

    private String getEndpointTypeString(int type) {
        switch (type) {
            case UsbConstants.USB_ENDPOINT_XFER_BULK: return "BULK";
            case UsbConstants.USB_ENDPOINT_XFER_CONTROL: return "CONTROL";
            case UsbConstants.USB_ENDPOINT_XFER_INT: return "INTERRUPT";
            case UsbConstants.USB_ENDPOINT_XFER_ISOC: return "ISOCHRONOUS";
            default: return "UNKNOWN (" + type + ")";
        }
    }
}
