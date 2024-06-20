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

package com.android.tradefed.cache.remote;

import static java.lang.Math.min;

import com.google.protobuf.ByteString;
import java.io.IOException;
import java.io.InputStream;
import java.util.NoSuchElementException;

/**
 * Splits a {@code blob} into one or more {@link Chunk}s of at most {@code chunkSize} bytes.
 *
 * <p>After {@code blob} has been fully consumed, that is until {@link #hasNext()} returns {@code
 * false}, the chunker closes the underlying data source (i.e. file) itself.
 */
public final class Chunker {

    /** A piece of a blob. */
    public static final class Chunk {

        private final long mOffset;
        private final ByteString mData;

        private Chunk(ByteString data, long offset) {
            mData = data;
            mOffset = offset;
        }

        public long getOffset() {
            return mOffset;
        }

        public ByteString getData() {
            return mData;
        }
    }

    private InputStream mBlob;
    private long mSize;
    private int mChunkSize;
    private long mOffset;
    private byte[] mChunkBuffer;

    public Chunker(InputStream blob, long size, int chunkSize) {
        mBlob = blob;
        mSize = size;
        mChunkSize = chunkSize;
        mOffset = 0;
        mChunkBuffer = new byte[(int) min(size, chunkSize)];
    }

    /**
     * Returns {@code true} if a subsequent call to {@link #next()} returns a {@link Chunk} object.
     */
    public boolean hasNext() {
        return mBlob != null;
    }

    /**
     * Returns the next {@link Chunk} or throws a {@link NoSuchElementException} if no data is left.
     *
     * <p>Always call {@link #hasNext()} before calling this method.
     *
     * <p>Zero byte inputs are treated special. Instead of throwing a {@link NoSuchElementException}
     * on the first call to {@link #next()}, a {@link Chunk} with an empty {@link ByteString} is
     * returned.
     */
    public Chunk next() throws IOException {
        if (!hasNext()) {
            throw new NoSuchElementException();
        }

        if (mSize == 0) {
            close();
            return new Chunk(ByteString.EMPTY, 0);
        }

        long offsetBefore = mOffset;

        int bytesRead = read();

        ByteString chunk = ByteString.copyFrom(mChunkBuffer, 0, bytesRead);

        mOffset += bytesRead;
        if (mOffset >= mSize) {
            close();
        }

        return new Chunk(chunk, offsetBefore);
    }

    /** Attempts reading at most a full chunk and stores it in the chunk buffer. */
    private int read() throws IOException {
        int count = 0;
        while (count < mChunkBuffer.length) {
            int c = mBlob.read(mChunkBuffer, count, mChunkBuffer.length - count);
            if (c < 0) {
                close();
                break;
            }
            count += c;
        }
        return count;
    }

    /** Closes the input stream. */
    private void close() throws IOException {
        if (mBlob != null) {
            mBlob.close();
            mBlob = null;
        }
    }
}
