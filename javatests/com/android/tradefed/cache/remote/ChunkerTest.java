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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.NoSuchElementException;
import java.util.Random;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link Chunker}. */
@RunWith(JUnit4.class)
public class ChunkerTest {
    @Test
    public void chunkingShouldWork() throws IOException {
        byte[] expectedData = new byte[21];
        new Random().nextBytes(expectedData);
        Chunker chunker =
                new Chunker(new ByteArrayInputStream(expectedData), expectedData.length, 10);
        ByteArrayOutputStream actualData = new ByteArrayOutputStream();

        Chunker.Chunk first = chunker.next();
        first.getData().writeTo(actualData);
        Chunker.Chunk second = chunker.next();
        second.getData().writeTo(actualData);
        Chunker.Chunk third = chunker.next();
        third.getData().writeTo(actualData);

        assertEquals(first.getData().size(), 10);
        assertEquals(first.getOffset(), 0);
        assertEquals(second.getData().size(), 10);
        assertEquals(second.getOffset(), 10);
        assertEquals(third.getData().size(), 1);
        assertEquals(third.getOffset(), 20);
        assertArrayEquals(actualData.toByteArray(), expectedData);
    }

    @Test
    public void hasNextReturnsTrue() throws IOException {
        byte[] data = new byte[10];
        Chunker chunker = new Chunker(new ByteArrayInputStream(data), 10, 10);

        boolean hasNext = chunker.hasNext();

        assertTrue(hasNext);
    }

    @Test
    public void nextShouldThrowIfNoMoreData() throws IOException {
        byte[] data = new byte[10];
        Chunker chunker = new Chunker(new ByteArrayInputStream(data), 10, 11);

        chunker.next();

        assertFalse(chunker.hasNext());
        assertThrows(NoSuchElementException.class, () -> chunker.next());
    }

    @Test
    public void nextWorksOnEmptyData() throws Exception {
        var inp =
                new ByteArrayInputStream(new byte[0]) {
                    private boolean closed;

                    @Override
                    public void close() throws IOException {
                        closed = true;
                        super.close();
                    }
                };
        Chunker chunker = new Chunker(inp, 0, 10);

        Chunker.Chunk next = chunker.next();

        assertNotNull(next);
        assertTrue(next.getData().isEmpty());
        assertEquals(next.getOffset(), 0);
        assertTrue(inp.closed);
    }
}
