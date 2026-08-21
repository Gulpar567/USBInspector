package com.gulpar567.usbinspector;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class DashboardFragment extends Fragment {

    private TextView tvStatus, tvDeviceInfo, tvOperator, tvSignal;
    private UsbController usbController;
    private ExecutorService executor;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private boolean isReceiverRegistered = false;

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (UsbController.ACTION_USB_PERMISSION.equals(action)) {
                synchronized (this) {
                    UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        if (device != null) {
                            loadModemDataAsync();
                        }
                    } else {
                        if (tvStatus != null) tvStatus.setText("Permission Denied by User");
                    }
                }
            }
        }
    };

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_dashboard, container, false);

        tvStatus = view.findViewById(R.id.tv_connection_status);
        tvDeviceInfo = view.findViewById(R.id.tv_device_info);
        tvOperator = view.findViewById(R.id.tv_operator);
        tvSignal = view.findViewById(R.id.tv_signal_strength);

        usbController = new UsbController(requireContext());
        executor = Executors.newSingleThreadExecutor();

        IntentFilter filter = new IntentFilter(UsbController.ACTION_USB_PERMISSION);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requireContext().registerReceiver(usbReceiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            requireContext().registerReceiver(usbReceiver, filter);
        }
        isReceiverRegistered = true;

        loadModemDataAsync();

        return view;
    }

    private void loadModemDataAsync() {
        if (executor == null || executor.isShutdown()) return;

        executor.execute(() -> {
            UsbDevice modem = usbController.findModem();

            if (modem == null) {
                mainHandler.post(() -> {
                    if (tvStatus != null) tvStatus.setText("No Modem Found");
                });
                return;
            }

            if (!usbController.hasPermission(modem)) {
                mainHandler.post(() -> {
                    if (tvStatus != null) {
                        tvStatus.setText("Requesting Permission...");
                        usbController.requestPermission(modem);
                    }
                });
                return;
            }

            mainHandler.post(() -> {
                if (tvStatus != null) tvStatus.setText("Connecting: " + modem.getDeviceName());
            });

            if (usbController.connect(modem)) {
                String atiResponse = usbController.sendAtCommand("ATI");
                String copsResponse = usbController.sendAtCommand("AT+COPS?");
                String csqResponse = usbController.sendAtCommand("AT+CSQ");

                mainHandler.post(() -> {
                    if (tvStatus != null) {
                        tvStatus.setText("Connected");
                        tvDeviceInfo.setText("Model: " + cleanResponse(atiResponse));
                        tvOperator.setText("Operator: " + parseOperator(copsResponse));
                        tvSignal.setText("Signal: " + parseSignal(csqResponse));
                    }
                });
            } else {
                mainHandler.post(() -> {
                    if (tvStatus != null) tvStatus.setText("Connection Failed");
                });
            }
        });
    }

    private String cleanResponse(String raw) {
        return raw.replace("OK", "").replace("\r", " ").replace("\n", " ").trim();
    }

    private String parseOperator(String cops) {
        if (cops.contains("+COPS:")) {
            String[] parts = cops.split(",");
            if (parts.length >= 3) {
                return parts[2].replace("\"", "").trim();
            }
        }
        return "Unknown";
    }

    private String parseSignal(String csq) {
        if (csq.contains("+CSQ:")) {
            String[] parts = csq.split(":");
            if (parts.length > 1) {
                String rssiStr = parts[1].split(",")[0].trim();
                try {
                    int rssi = Integer.parseInt(rssiStr);
                    if (rssi == 99) return "Unknown";
                    int dbm = -113 + (2 * rssi);
                    return dbm + " dBm (RSSI: " + rssi + ")";
                } catch (NumberFormatException ignored) {}
            }
        }
        return "-- dBm";
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();

        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
        if (isReceiverRegistered) {
            try {
                requireContext().unregisterReceiver(usbReceiver);
            } catch (Exception ignored) {}
            isReceiverRegistered = false;
        }
        if (usbController != null) {
            usbController.disconnect();
        }
    }
}
