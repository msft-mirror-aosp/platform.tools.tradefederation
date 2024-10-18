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

class PackEntry {
    public static final int SIZE = 104; // 4 bytes per field
    public int type;
    public byte[] name;
    public byte[] product;
    public long offset;
    public long size;
    public int slotted;
    public int crc32;

    public PackEntry(ByteBuffer buffer) {
        type = buffer.getInt();
        name = new byte[36];
        buffer.get(name);
        product = new byte[40];
        buffer.get(product);
        offset = buffer.getLong();
        size = buffer.getLong();
        slotted = buffer.getInt();
        crc32 = buffer.getInt();
    }
}
