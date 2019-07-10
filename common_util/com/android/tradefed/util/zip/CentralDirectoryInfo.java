/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.tradefed.util.zip;

import com.android.tradefed.util.ByteArrayUtil;

import com.google.common.annotations.VisibleForTesting;

import java.io.IOException;
import java.util.Arrays;

/**
 * CentralDirectoryInfo is a class containing the information of a file/folder inside a zip file.
 *
 * <p>Overall zipfile format: [Local file header + Compressed data [+ Extended local header]?]*
 * [Central directory]* [End of central directory record]
 *
 * <p>Refer to following link for more details: https://en.wikipedia.org/wiki/Zip_(file_format)
 */
public final class CentralDirectoryInfo {

    private static final byte[] CENTRAL_DIRECTORY_SIGNATURE = {0x50, 0x4b, 0x01, 0x02};

    private int mCompressionMethod;
    private long mCrc;
    private long mCompressedSize;
    private long mUncompressedSize;
    private long mLocalHeaderOffset;
    private int mInternalFileAttributes;
    private long mExternalFileAttributes;
    private String mFileName;
    private int mFileNameLength;
    private int mExtraFieldLength;
    private int mFileCommentLength;

    /** Get the compression method. */
    public int getCompressionMethod() {
        return mCompressionMethod;
    }

    /** Set the compression method. */
    public void setCompressionMethod(int compressionMethod) {
        mCompressionMethod = compressionMethod;
    }

    /** Get the CRC of the file. */
    public long getCrc() {
        return mCrc;
    }

    /** Set the CRC of the file. */
    public void setCrc(long crc) {
        mCrc = crc;
    }

    /** Get the compressed size. */
    public int getCompressedSize() {
        return (int) mCompressedSize;
    }

    /** Set the compressed size. */
    public void setCompressedSize(long compressionSize) {
        mCompressedSize = compressionSize;
    }

    /** Get the uncompressed size. */
    public long getUncompressedSize() {
        return mUncompressedSize;
    }

    /** Set the uncompressed size. */
    public void setUncompressedSize(long uncompressedSize) {
        mUncompressedSize = uncompressedSize;
    }

    /** Get the offset of local file header entry. */
    public long getLocalHeaderOffset() {
        return mLocalHeaderOffset;
    }

    /** Set the offset of local file header entry. */
    public void setLocalHeaderOffset(long localHeaderOffset) {
        mLocalHeaderOffset = localHeaderOffset;
    }

    /** Get the internal file attributes. */
    public int getInternalFileAttributes() {
        return mInternalFileAttributes;
    }

    /** Set the internal file attributes. */
    public void setInternalFileAttributes(int internalFileAttributes) {
        mInternalFileAttributes = internalFileAttributes;
    }

    /** Get the external file attributes. */
    public long getExternalFileAttributes() {
        return mExternalFileAttributes;
    }

    /** Set the external file attributes. */
    public void setExternalFileAttributes(long externalFileAttributes) {
        mExternalFileAttributes = externalFileAttributes;
    }

    /** Get the Linux file permission, stored in the last 9 bits of external file attributes. */
    public int getFilePermission() {
        return ((int) mExternalFileAttributes & (0777 << 16L)) >> 16L;
    }

    /** Get the file name including the relative path. */
    public String getFileName() {
        return mFileName;
    }

    /** Set the file name including the relative path. */
    public void setFileName(String fileName) {
        mFileName = fileName;
    }

    /** Get the file name length. */
    public int getFileNameLength() {
        return mFileNameLength;
    }

    /** Set the file name length. */
    public void setFileNameLength(int fileNameLength) {
        mFileNameLength = fileNameLength;
    }

    /** Get the extra field length. */
    public int getExtraFieldLength() {
        return mExtraFieldLength;
    }

    /** Set the extra field length. */
    public void setExtraFieldLength(int extraFieldLength) {
        mExtraFieldLength = extraFieldLength;
    }

    /** Get the file comment length. */
    public int getFileCommentLength() {
        return mFileCommentLength;
    }

    /** Set the file comment length. */
    public void setFileCommentLength(int fileCommentLength) {
        mFileCommentLength = fileCommentLength;
    }

    /** Get the size of the central directory entry. */
    public int getInfoSize() {
        return 46 + mFileNameLength + mExtraFieldLength + mFileCommentLength;
    }

    /** Default constructor used for unit test. */
    @VisibleForTesting
    protected CentralDirectoryInfo() {}

    /**
     * Constructor to collect the information of a file entry inside zip file.
     *
     * @param data {@code byte[]} of data that contains the information of a file entry.
     * @param startOffset start offset of the information block.
     * @throws IOException
     */
    public CentralDirectoryInfo(byte[] data, int startOffset) throws IOException {
        // Central directory:
        //    Offset   Length   Contents
        //      0      4 bytes  Central file header signature (0x02014b50)
        //      4      2 bytes  Version made by
        //      6      2 bytes  Version needed to extract
        //      8      2 bytes  General purpose bit flag
        //     10      2 bytes  Compression method
        //     12      2 bytes  Last mod file time
        //     14      2 bytes  Last mod file date
        //     16      4 bytes  CRC-32
        //     20      4 bytes  Compressed size
        //     24      4 bytes  Uncompressed size
        //     28      2 bytes  Filename length (f)
        //     30      2 bytes  Extra field length (e)
        //     32      2 bytes  File comment length (c)
        //     34      2 bytes  Disk number start
        //     36      2 bytes  Internal file attributes
        //     38      4 bytes  External file attributes (file permission stored in the last 9 bits)
        //     42      4 bytes  Relative offset of local header
        //     46     (f)bytes  Filename
        //            (e)bytes  Extra field
        //            (c)bytes  File comment

        // Check signature
        if (!Arrays.equals(
                CENTRAL_DIRECTORY_SIGNATURE,
                Arrays.copyOfRange(data, startOffset, startOffset + 4))) {
            throw new IOException("Invalid central directory info for zip file is found.");
        }
        mCompressionMethod = ByteArrayUtil.getInt(data, startOffset + 10, 2);
        mCrc = ByteArrayUtil.getLong(data, startOffset + 16, 4);
        mCompressedSize = ByteArrayUtil.getLong(data, startOffset + 20, 4);
        mUncompressedSize = ByteArrayUtil.getLong(data, startOffset + 24, 4);
        mInternalFileAttributes = ByteArrayUtil.getInt(data, startOffset + 36, 2);
        mExternalFileAttributes = ByteArrayUtil.getLong(data, startOffset + 38, 4);
        mLocalHeaderOffset = ByteArrayUtil.getLong(data, startOffset + 42, 4);
        mFileNameLength = ByteArrayUtil.getInt(data, startOffset + 28, 2);
        mFileName = ByteArrayUtil.getString(data, startOffset + 46, mFileNameLength);
        mExtraFieldLength = ByteArrayUtil.getInt(data, startOffset + 30, 2);
        mFileCommentLength = ByteArrayUtil.getInt(data, startOffset + 32, 2);
    }
}
