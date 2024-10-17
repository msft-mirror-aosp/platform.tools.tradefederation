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

import com.android.tradefed.log.LogUtil.CLog;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Follow the specification of bootloader to unpack it. */
public class FastbootPack {

    /** Utility to unpack a bootloader file per specification. Similar to fastboot code. */
    public static void unpack(
            File bootloader, File outputDir, String product, boolean unpackVersion)
            throws IOException {
        PackHeader packHeader = readPackHeader(bootloader);
        List<PackEntry> packEntries = new ArrayList<>();

        try (FileInputStream fis = new FileInputStream(bootloader);
                FileChannel fileChannel = fis.getChannel()) {
            fileChannel.position(packHeader.headerSize);
            for (int i = 0; i < packHeader.totalEntries; i++) {
                packEntries.add(readPackEntry(fileChannel));
            }
        }

        for (PackEntry packEntry : packEntries) {
            if (product != null && !productMatch(packEntry.product, product)) {
                continue;
            }

            String name = bytesToString(packEntry.name);
            CLog.d(
                    "Unpacking "
                            + name
                            + " (size: "
                            + packEntry.size
                            + ", offset: "
                            + packEntry.offset
                            + ")");
            try (FileInputStream fis = new FileInputStream(bootloader);
                    FileChannel fileChannel = fis.getChannel()) {
                fileChannel.position(packEntry.offset);
                File outputFile = new File(outputDir, name + ".img");
                try (FileOutputStream fos = new FileOutputStream(outputFile);
                        FileChannel outputChannel = fos.getChannel()) {
                    outputChannel.transferFrom(fileChannel, 0, packEntry.size);
                }
            }
        }

        if (unpackVersion) {
            File versionFile = new File(outputDir, "version.txt");
            try (FileOutputStream fos = new FileOutputStream(versionFile)) {
                fos.write(packHeader.packVersion);
            }
        }
    }

    private static boolean productMatch(byte[] product, String targetProduct) {
        String productString = bytesToString(product);
        return Arrays.asList(productString.split("\\|")).contains(targetProduct);
    }

    private static PackHeader readPackHeader(File file) throws IOException {
        try (FileInputStream fis = new FileInputStream(file);
                FileChannel fileChannel = fis.getChannel()) {
            ByteBuffer buffer = ByteBuffer.allocate(PackHeader.SIZE);
            buffer.order(ByteOrder.LITTLE_ENDIAN);
            fileChannel.read(buffer);
            buffer.flip();
            return new PackHeader(buffer);
        }
    }

    private static PackEntry readPackEntry(FileChannel fileChannel) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(PackEntry.SIZE);
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        fileChannel.read(buffer);
        buffer.flip();
        return new PackEntry(buffer);
    }

    private static String bytesToString(byte[] bytes) {
        return new String(bytes).trim();
    }
}
