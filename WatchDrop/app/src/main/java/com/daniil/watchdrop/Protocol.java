package com.daniil.watchdrop;

import java.util.UUID;

final class Protocol {
    static final String SERVICE_NAME = "WatchDrop";
    static final UUID SERVICE_UUID = UUID.fromString("73b5cc40-a65f-4b5e-a8cd-80d44f54b143");

    static final int MAGIC = 0x57445250; // WDRP
    static final int VERSION = 1;
    static final int ACK_OK = 0x4F4B0001;
    static final int COMPLETE = 0x444F4E45; // DONE

    static final int MAX_FILES_PER_TRANSFER = 200;
    static final long MAX_FILE_SIZE = 20L * 1024L * 1024L * 1024L;

    private Protocol() {}
}
