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

    private TextView tvStatus, tvDiagnosticLog;
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
                            runDiagnosticsAsync();
                        }
                    } else {
                        if (tvStatus != null) tvStatus.setText("Status: Permission Denied");
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
        tvDiagnosticLog = view.findViewById(R.id.tv_diagnostic_log);

        usbController = new UsbController(requireContext());
        executor = Executors.newSingleThreadExecutor();

        IntentFilter filter = new IntentFilter(UsbController.ACTION_USB_PERMISSION);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requireContext().registerReceiver(usbReceiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            requireContext().registerReceiver(usbReceiver, filter);
        }
        isReceiverRegistered = true;

        runDiagnosticsAsync();

        return view;
    }

    private void runDiagnosticsAsync() {
        if (executor == null || executor.isShutdown()) return;

        executor.execute(() -> {
            UsbDevice modem = usbController.findModem();

            if (modem == null) {
                mainHandler.post(() -> {
                    if (tvStatus != null) tvStatus.setText("Status: No Modem Found");
                });
                return;
            }

            if (!usbController.hasPermission(modem)) {
                mainHandler.post(() -> {
                    if (tvStatus != null) {
                        tvStatus.setText("Status: Requesting Permission...");
                        usbController.requestPermission(modem);
                    }
                });
                return;
            }

            mainHandler.post(() -> {
                if (tvStatus != null) tvStatus.setText("Status: Scanning USB Topology...");
            });

            String topologyLog = usbController.scanUsbDeviceTopology(modem);

            mainHandler.post(() -> {
                if (tvStatus != null) tvStatus.setText("Status: Scan Complete");
                if (tvDiagnosticLog != null) tvDiagnosticLog.setText(topologyLog);
            });
        });
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
    }
}
