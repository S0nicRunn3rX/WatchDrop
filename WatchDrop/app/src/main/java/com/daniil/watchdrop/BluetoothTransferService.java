package com.daniil.watchdrop;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.provider.OpenableColumns;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class BluetoothTransferService extends Service {
    public static final String ACTION_START_LISTENER = "com.daniil.watchdrop.START_LISTENER";
    public static final String ACTION_STOP_LISTENER = "com.daniil.watchdrop.STOP_LISTENER";
    public static final String ACTION_SEND_FILES = "com.daniil.watchdrop.SEND_FILES";
    public static final String ACTION_TRANSFER_STATUS = "com.daniil.watchdrop.TRANSFER_STATUS";

    public static final String EXTRA_DEVICE_ADDRESS = "device_address";
    public static final String EXTRA_URIS = "uris";
    public static final String EXTRA_TRANSFER_ID = "transfer_id";
    public static final String EXTRA_STATUS = "status";
    public static final String EXTRA_MESSAGE = "message";

    public static final String STATUS_PROGRESS = "progress";
    public static final String STATUS_SUCCESS = "success";
    public static final String STATUS_ERROR = "error";

    private static final String CHANNEL_ID = "watchdrop_transfer";
    private static final int NOTIFICATION_ID = 7315;
    private static final int BUFFER_SIZE = 64 * 1024;

    private final ExecutorService listenerExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService transferExecutor = Executors.newCachedThreadPool();
    private final AtomicBoolean listenerRunning = new AtomicBoolean(false);
    private final AtomicInteger activeTransfers = new AtomicInteger(0);

    private volatile BluetoothServerSocket serverSocket;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!PermissionUtils.hasBluetoothConnect(this)) {
            stopSelf();
            return START_NOT_STICKY;
        }

        String action = intent == null ? null : intent.getAction();
        ensureForeground("WatchDrop готов к работе");

        if (ACTION_STOP_LISTENER.equals(action)) {
            AppPrefs.setReceiveEnabled(this, false);
            stopListener();
            if (activeTransfers.get() == 0) stopServiceCompletely();
            return START_NOT_STICKY;
        }

        if (ACTION_START_LISTENER.equals(action)) {
            AppPrefs.setReceiveEnabled(this, true);
            startListener();
        } else if (ACTION_SEND_FILES.equals(action)) {
            if (AppPrefs.isReceiveEnabled(this)) startListener();
            handleSendIntent(intent);
        } else if (AppPrefs.isReceiveEnabled(this)) {
            startListener();
        }

        return AppPrefs.isReceiveEnabled(this) ? START_STICKY : START_NOT_STICKY;
    }

    private void handleSendIntent(Intent intent) {
        String address = intent.getStringExtra(EXTRA_DEVICE_ADDRESS);
        String transferId = intent.getStringExtra(EXTRA_TRANSFER_ID);
        ArrayList<Uri> uris;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            uris = intent.getParcelableArrayListExtra(EXTRA_URIS, Uri.class);
        } else {
            //noinspection deprecation
            uris = intent.getParcelableArrayListExtra(EXTRA_URIS);
        }

        if (address == null || transferId == null || uris == null || uris.isEmpty()) {
            sendStatus(transferId, STATUS_ERROR, "Не удалось получить данные для отправки");
            maybeStopAfterTransfer();
            return;
        }

        activeTransfers.incrementAndGet();
        transferExecutor.execute(() -> {
            try {
                sendFiles(address, uris, transferId);
                AppPrefs.setPreferredDevice(this, address);
                sendStatus(transferId, STATUS_SUCCESS, "Передача завершена");
                updateNotification(AppPrefs.isReceiveEnabled(this)
                        ? "Приём файлов включён"
                        : "Передача завершена");
            } catch (Exception e) {
                String text = e.getMessage();
                if (text == null || text.trim().isEmpty()) text = e.getClass().getSimpleName();
                sendStatus(transferId, STATUS_ERROR, "Ошибка передачи: " + text);
                updateNotification("Ошибка передачи");
            } finally {
                activeTransfers.decrementAndGet();
                maybeStopAfterTransfer();
            }
        });
    }

    private void startListener() {
        if (!listenerRunning.compareAndSet(false, true)) return;

        listenerExecutor.execute(() -> {
            while (listenerRunning.get()) {
                BluetoothAdapter adapter = getBluetoothAdapter();
                if (adapter == null || !adapter.isEnabled()) {
                    sleepQuietly(3000);
                    continue;
                }

                try {
                    serverSocket = adapter.listenUsingRfcommWithServiceRecord(
                            Protocol.SERVICE_NAME, Protocol.SERVICE_UUID);
                    updateNotification("Приём файлов включён");

                    while (listenerRunning.get()) {
                        BluetoothSocket socket = serverSocket.accept();
                        if (socket != null) {
                            transferExecutor.execute(() -> receiveFiles(socket));
                        }
                    }
                } catch (IOException | SecurityException e) {
                    if (listenerRunning.get()) sleepQuietly(2000);
                } finally {
                    closeServerSocket();
                }
            }
        });
    }

    private void stopListener() {
        listenerRunning.set(false);
        closeServerSocket();
    }

    private void closeServerSocket() {
        BluetoothServerSocket socket = serverSocket;
        serverSocket = null;
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    private void receiveFiles(BluetoothSocket socket) {
        String remoteName = "устройство";
        try {
            remoteName = safeDeviceName(socket.getRemoteDevice());
            DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
            DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));

            int magic = in.readInt();
            int version = in.readInt();
            int count = in.readInt();
            if (magic != Protocol.MAGIC) throw new IOException("Неверная сигнатура WatchDrop");
            if (version != Protocol.VERSION) throw new IOException("Несовместимая версия протокола");
            if (count < 1 || count > Protocol.MAX_FILES_PER_TRANSFER) {
                throw new IOException("Некорректное число файлов: " + count);
            }

            for (int i = 0; i < count; i++) {
                String name = sanitizeFileName(in.readUTF());
                String mime = sanitizeMime(in.readUTF());
                long size = in.readLong();
                if (size < 0 || size > Protocol.MAX_FILE_SIZE) {
                    throw new IOException("Недопустимый размер файла");
                }

                updateNotification("Получение " + (i + 1) + "/" + count + ": " + name);
                saveIncomingFile(in, name, mime, size);
                out.writeInt(Protocol.ACK_OK);
                out.flush();
            }

            out.writeInt(Protocol.COMPLETE);
            out.flush();
            updateNotification("Получено файлов: " + count + " от " + remoteName);
        } catch (EOFException e) {
            updateNotification("Передача от " + remoteName + " была прервана");
        } catch (Exception e) {
            updateNotification("Ошибка приёма от " + remoteName);
        } finally {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    private void sendFiles(String address, ArrayList<Uri> uris, String transferId) throws Exception {
        if (uris.size() > Protocol.MAX_FILES_PER_TRANSFER) {
            throw new IOException("Слишком много файлов за одну передачу");
        }

        BluetoothAdapter adapter = getBluetoothAdapter();
        if (adapter == null || !adapter.isEnabled()) throw new IOException("Bluetooth выключен");

        BluetoothDevice device = adapter.getRemoteDevice(address);
        if (device.getBondState() != BluetoothDevice.BOND_BONDED) {
            throw new IOException("Устройства не сопряжены");
        }

        String deviceName = safeDeviceName(device);
        sendStatus(transferId, STATUS_PROGRESS, "Подключение к " + deviceName + "…");
        updateNotification("Подключение к " + deviceName);

        try (BluetoothSocket socket = device.createRfcommSocketToServiceRecord(Protocol.SERVICE_UUID)) {
            socket.connect();
            DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
            DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));

            out.writeInt(Protocol.MAGIC);
            out.writeInt(Protocol.VERSION);
            out.writeInt(uris.size());
            out.flush();

            for (int i = 0; i < uris.size(); i++) {
                PreparedFile file = prepareFile(uris.get(i));
                try {
                    if (file.size > Protocol.MAX_FILE_SIZE) {
                        throw new IOException("Файл слишком большой: " + file.name);
                    }

                    String progress = "Отправка " + (i + 1) + "/" + uris.size() + ": " + file.name;
                    sendStatus(transferId, STATUS_PROGRESS, progress);
                    updateNotification(progress);

                    out.writeUTF(file.name);
                    out.writeUTF(file.mime);
                    out.writeLong(file.size);

                    try (InputStream fileInput = file.openInput(this)) {
                        copyExactly(fileInput, out, file.size);
                    }
                    out.flush();

                    int ack = in.readInt();
                    if (ack != Protocol.ACK_OK) throw new IOException("Получатель не подтвердил файл");
                } finally {
                    file.cleanup();
                }
            }

            int complete = in.readInt();
            if (complete != Protocol.COMPLETE) throw new IOException("Получатель не подтвердил завершение");
        }
    }

    private PreparedFile prepareFile(Uri uri) throws IOException {
        ContentResolver resolver = getContentResolver();
        String name = null;
        long size = -1;

        try (Cursor cursor = resolver.query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE},
                null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                int sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (nameIndex >= 0 && !cursor.isNull(nameIndex)) name = cursor.getString(nameIndex);
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex);
            }
        } catch (Exception ignored) {
        }

        if (name == null || name.trim().isEmpty()) name = "file_" + System.currentTimeMillis();
        name = sanitizeFileName(name);
        String mime = resolver.getType(uri);
        mime = sanitizeMime(mime);

        try (ParcelFileDescriptor pfd = resolver.openFileDescriptor(uri, "r")) {
            if (pfd != null) {
                long stat = pfd.getStatSize();
                if (stat >= 0) size = stat;
            }
        } catch (Exception ignored) {
        }

        if (size >= 0) return new PreparedFile(uri, null, name, mime, size);

        File temp = File.createTempFile("watchdrop_", ".send", getCacheDir());
        try (InputStream input = resolver.openInputStream(uri);
             OutputStream output = new BufferedOutputStream(new FileOutputStream(temp))) {
            if (input == null) throw new IOException("Не удалось открыть " + name);
            copyAll(input, output);
        } catch (Exception e) {
            //noinspection ResultOfMethodCallIgnored
            temp.delete();
            if (e instanceof IOException) throw (IOException) e;
            throw new IOException(e);
        }
        return new PreparedFile(null, temp, name, mime, temp.length());
    }

    private void saveIncomingFile(DataInputStream input, String fileName, String mime, long size)
            throws IOException {
        ContentResolver resolver = getContentResolver();
        Uri collection;
        String relativePath;

        if (mime.startsWith("image/")) {
            collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
            relativePath = "Pictures/WatchDrop";
        } else if (mime.startsWith("video/")) {
            collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
            relativePath = "Movies/WatchDrop";
        } else if (mime.startsWith("audio/")) {
            collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
            relativePath = "Music/WatchDrop";
        } else {
            collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI;
            relativePath = "Download/WatchDrop";
        }

        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
        values.put(MediaStore.MediaColumns.MIME_TYPE, mime);
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath);
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);

        Uri uri = resolver.insert(collection, values);
        if (uri == null) throw new IOException("Не удалось создать файл " + fileName);

        boolean success = false;
        try (OutputStream raw = resolver.openOutputStream(uri, "w")) {
            if (raw == null) throw new IOException("Не удалось открыть файл для записи");
            BufferedOutputStream output = new BufferedOutputStream(raw);
            copyExactly(input, output, size);
            output.flush();
            success = true;
        } finally {
            if (success) {
                ContentValues done = new ContentValues();
                done.put(MediaStore.MediaColumns.IS_PENDING, 0);
                resolver.update(uri, done, null, null);
            } else {
                resolver.delete(uri, null, null);
            }
        }
    }

    private void copyExactly(InputStream input, OutputStream output, long bytes) throws IOException {
        byte[] buffer = new byte[BUFFER_SIZE];
        long remaining = bytes;
        while (remaining > 0) {
            int wanted = (int) Math.min(buffer.length, remaining);
            int read = input.read(buffer, 0, wanted);
            if (read < 0) throw new EOFException("Файл закончился раньше заявленного размера");
            output.write(buffer, 0, read);
            remaining -= read;
        }
    }

    private void copyAll(InputStream input, OutputStream output) throws IOException {
        byte[] buffer = new byte[BUFFER_SIZE];
        int read;
        while ((read = input.read(buffer)) >= 0) {
            output.write(buffer, 0, read);
        }
    }

    private String sanitizeFileName(String name) {
        if (name == null || name.trim().isEmpty()) return "file_" + System.currentTimeMillis();
        String cleaned = name.replace('/', '_').replace('\\', '_').replace('\0', '_').trim();
        if (cleaned.isEmpty()) cleaned = "file_" + System.currentTimeMillis();
        if (cleaned.length() > 180) cleaned = cleaned.substring(0, 180);
        return cleaned;
    }

    private String sanitizeMime(String mime) {
        if (mime == null || mime.trim().isEmpty() || mime.length() > 200 || !mime.contains("/")) {
            return "application/octet-stream";
        }
        return mime.toLowerCase(Locale.ROOT);
    }

    private BluetoothAdapter getBluetoothAdapter() {
        BluetoothManager manager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        return manager == null ? null : manager.getAdapter();
    }

    private String safeDeviceName(BluetoothDevice device) {
        try {
            String name = device.getName();
            return (name == null || name.trim().isEmpty()) ? device.getAddress() : name;
        } catch (SecurityException e) {
            return "Bluetooth-устройство";
        }
    }

    private void sendStatus(String transferId, String status, String message) {
        if (transferId == null) return;
        Intent intent = new Intent(ACTION_TRANSFER_STATUS);
        intent.setPackage(getPackageName());
        intent.putExtra(EXTRA_TRANSFER_ID, transferId);
        intent.putExtra(EXTRA_STATUS, status);
        intent.putExtra(EXTRA_MESSAGE, message);
        sendBroadcast(intent);
    }

    private void createNotificationChannel() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Передача файлов WatchDrop",
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Фоновый приём и передача файлов по Bluetooth");
        manager.createNotificationChannel(channel);
    }

    private void ensureForeground(String text) {
        Notification notification = buildNotification(text);
        startForeground(NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
    }

    private void updateNotification(String text) {
        try {
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.notify(NOTIFICATION_ID, buildNotification(text));
        } catch (Exception ignored) {
        }
    }

    private Notification buildNotification(String text) {
        Intent launchIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("WatchDrop")
                .setContentText(text)
                .setContentIntent(pendingIntent)
                .setOngoing(AppPrefs.isReceiveEnabled(this) || activeTransfers.get() > 0)
                .setCategory(Notification.CATEGORY_SERVICE)
                .build();
    }

    private void maybeStopAfterTransfer() {
        if (!AppPrefs.isReceiveEnabled(this) && activeTransfers.get() == 0) {
            stopServiceCompletely();
        }
    }

    private void stopServiceCompletely() {
        stopListener();
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void onDestroy() {
        stopListener();
        listenerExecutor.shutdownNow();
        transferExecutor.shutdownNow();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private static final class PreparedFile {
        final Uri uri;
        final File tempFile;
        final String name;
        final String mime;
        final long size;

        PreparedFile(Uri uri, File tempFile, String name, String mime, long size) {
            this.uri = uri;
            this.tempFile = tempFile;
            this.name = name;
            this.mime = mime;
            this.size = size;
        }

        InputStream openInput(Context context) throws IOException {
            if (tempFile != null) return new BufferedInputStream(new FileInputStream(tempFile));
            InputStream input = context.getContentResolver().openInputStream(uri);
            if (input == null) throw new IOException("Не удалось открыть " + name);
            return new BufferedInputStream(input);
        }

        void cleanup() {
            if (tempFile != null) {
                //noinspection ResultOfMethodCallIgnored
                tempFile.delete();
            }
        }
    }
}
