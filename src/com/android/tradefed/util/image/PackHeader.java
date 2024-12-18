/*
 * Copyright (C) 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.tradefed.util.image;

import java.nio.ByteBuffer;

class PackHeader {
    public static final int SIZE = 112; // 4 bytes per field
    public int magic;
    public int version;
    public int headerSize;
    public int entryHeaderSize;
    public byte[] platform;
    public byte[] packVersion;
    public int slotType;
    public int dataAlign;
    public int totalEntries;
    public int totalSize;

    public PackHeader(ByteBuffer buffer) {
        magic = buffer.getInt();
        version = buffer.getInt();
        headerSize = buffer.getInt();
        entryHeaderSize = buffer.getInt();
        platform = new byte[16];
        buffer.get(platform);
        packVersion = new byte[64];
        buffer.get(packVersion);
        slotType = buffer.getInt();
        dataAlign = buffer.getInt();
        totalEntries = buffer.getInt();
        totalSize = buffer.getInt();
    }
}
