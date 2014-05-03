//Copyright 2013, Nathan Tippy
//See LICENSE file for BSD license details.
//Send support requests to http://www.ociweb.com/contact
package com.ociweb.jfast.primitive;

import java.io.IOException;

import com.ociweb.jfast.error.FASTException;
import com.ociweb.jfast.field.TokenBuilder;

/**
 * PrimitiveReader
 * 
 * Must be final and not implement any interface or be abstract. In-lining the
 * primitive methods of this class provides much of the performance needed by
 * this library.
 * 
 * 
 * @author Nathan Tippy
 * 
 */

public final class PrimitiveReader {

    // Note: only the type/opp combos used will get in-lined, this small
    // footprint will fit in execution cache.
    // if we in-line too much the block will be to large and may spill.

    private final FASTInput input;
    private long totalReader;
    private final byte[] buffer;

    private final byte[] invPmapStack;
    private int invPmapStackDepth;

    private int position;
    private int limit;

    // both bytes but class def likes int much better for alignment
    private byte pmapIdx = -1;
    private byte bitBlock = 0;

    public final void reset() {
        totalReader = 0;
        position = 0;
        limit = 0;
        pmapIdx = -1;
        invPmapStackDepth = invPmapStack.length - 2;

    }

    public PrimitiveReader(FASTInput input) {
        this(2048, input, 32);
    }

    public PrimitiveReader(int initBufferSize, FASTInput input, int maxPMapCount) {
        this.input = input;
        this.buffer = new byte[initBufferSize];

        this.position = 0;
        this.limit = 0;
        this.invPmapStack = new byte[maxPMapCount];
        this.invPmapStackDepth = invPmapStack.length - 2;

        input.init(this.buffer);
    }

    public final long totalRead() {
        return totalReader;
    }

    public final int bytesReadyToParse() {
        return limit - position;
    }

    public final void fetch() {
        fetch(0, this);
    }

    // Will not return until the need is met because the parser has
    // determined that we can not continue until this data is provided.
    // this call may however read in more than the need because its ready
    // and convenient to reduce future calls.
    private static void fetch(int need, PrimitiveReader reader) {
        int count = 0;
        need = fetchAvail(need, reader);
        while (need > 0) { // TODO: C, if orignial need was zero should also
                           // compact?
            if (0 == count++) {

                // compact and prep for data spike

            } else {
                if (count < 10) {
                    Thread.yield();
                    // TODO: C, if we are in the middle of parsing a field this
                    // becomes a blocking read and requires a timeout and throw.

                } else {
                    try {
                        Thread.sleep(0, 100);
                    } catch (InterruptedException e) {
                    }
                }
            }

            need = fetchAvail(need, reader);
        }

    }

    private static int fetchAvail(int need, PrimitiveReader reader) {
        if (reader.position >= reader.limit) {
            reader.position = reader.limit = 0;
        }
        int remainingSpace = reader.buffer.length - reader.limit;
        if (need <= remainingSpace) {
            // fill remaining space if possible to reduce fetch later

            int filled = reader.input.fill(reader.limit, remainingSpace);

            //
            reader.totalReader += filled;
            reader.limit += filled;
            //
            return need - filled;
        } else {
            return noRoomOnFetch(need, reader);
        }
    }

    private static int noRoomOnFetch(int need, PrimitiveReader reader) {
        // not enough room at end of buffer for the need
        int populated = reader.limit - reader.position;
        int reqiredSize = need + populated;

        assert (reader.buffer.length >= reqiredSize) : "internal buffer is not large enough, requres " + reqiredSize
                + " bytes";

        System.arraycopy(reader.buffer, reader.position, reader.buffer, 0, populated);
        // fill and return

        int filled = reader.input.fill(populated, reader.buffer.length - populated);

        reader.position = 0;
        reader.totalReader += filled;
        reader.limit = populated + filled;

        return need - filled;

    }

    public final void readByteData(byte[] target, int offset, int length) {
        // ensure all the bytes are in the buffer before calling visitor
        if (limit - position < length) {
            fetch(length, this);
        }
        System.arraycopy(buffer, position, target, offset, length);
        position += length;
    }

    // ///////////////
    // pmapStructure
    // 1 2 3 4 5 D ? I 2 3 4 X X
    // 0 0 0 0 1 D ? I 0 0 1 X X
    //
    // D delta to last position
    // I pmapIdx of last stack frame
    // //
    // called at the start of each group unless group knows it has no pmap
    public static final void openPMap(final int pmapMaxSize, PrimitiveReader reader) {
        if (reader.position >= reader.limit) {
            fetch(1, reader);
        }
        // push the old index for resume
        reader.invPmapStack[reader.invPmapStackDepth - 1] = (byte) reader.pmapIdx;

        int k = reader.invPmapStackDepth -= (pmapMaxSize + 2);
        reader.bitBlock = reader.buffer[reader.position];
        //TODO: A, this is a constant for many templates and can be injected? still its a copy!.
        k = walkPMapLength(pmapMaxSize, k, reader.invPmapStack, reader);
        reader.invPmapStack[k] = (byte) (3 + pmapMaxSize + (reader.invPmapStackDepth - k));

        // set next bit to read
        reader.pmapIdx = 6;
    }

    private static int walkPMapLength(final int pmapMaxSize, int k, byte[] pmapStack, PrimitiveReader reader) {
        if (reader.limit - reader.position > pmapMaxSize) {
            if ((pmapStack[k++] = reader.buffer[reader.position++]) >= 0) {
                if ((pmapStack[k++] = reader.buffer[reader.position++]) >= 0) {
                    do {
                    } while ((pmapStack[k++] = reader.buffer[reader.position++]) >= 0);
                }
            }
        } else {
            k = openPMapSlow(k,reader);
        }
        return k;
    }

    private static int openPMapSlow(int k, PrimitiveReader reader) {
        // must use slow path because we are near the end of the buffer.
        do {
            if (reader.position >= reader.limit) {
                fetch(1, reader);
            }
            // System.err.println("*pmap:"+Integer.toBinaryString(0xFF&buffer[position]));
        } while ((reader.invPmapStack[k++] = reader.buffer[reader.position++]) >= 0);
        return k;
    }

    //TODO: X, by changing to static calls the vtable lookup will go away and it will be "more like" the eventual Julia code we want to produce.
    
    public static byte popPMapBit(PrimitiveReader reader) {//Invoked 100's of millions of times, must be tight.
        byte pidx = reader.pmapIdx; 
        if (pidx > 0 || (pidx == 0 && reader.bitBlock < 0)) {
            // Frequent, 6 out of every 7 plus the last bit block
            reader.pmapIdx = (byte) (pidx - 1);
            return (byte) (1 & (reader.bitBlock >>> pidx));
        } else {
            return (pidx >= 0 ? popPMapBitLow(reader.bitBlock, reader) : 0); //detect next byte or continue with zeros.
        }
    }

    private static byte popPMapBitLow(byte bb, PrimitiveReader reader) {
        // SOMETIMES one of 7 we need to move up to the next byte
        // System.err.println(invPmapStackDepth);
        reader.pmapIdx = 6;
        reader.bitBlock = reader.invPmapStack[++reader.invPmapStackDepth]; //TODO: X, Set both bytes togheter? may speed up
        return (byte) (1 & bb);
    }

    // called at the end of each group
    public static final void closePMap(PrimitiveReader reader) {
        // assert(bitBlock<0);
        assert (reader.invPmapStack[reader.invPmapStackDepth + 1] >= 0);
        reader.bitBlock = reader.invPmapStack[reader.invPmapStackDepth += (reader.invPmapStack[reader.invPmapStackDepth + 1])];
        reader.pmapIdx = reader.invPmapStack[reader.invPmapStackDepth - 1];

    }

    // ///////////////////////////////////
    // ///////////////////////////////////
    // ///////////////////////////////////

    public static final long readLongSigned(PrimitiveReader reader) {
        return readLongSignedPrivate(reader);
    }

    private static long readLongSignedPrivate(PrimitiveReader reader) {//Invoked 100's of millions of times, must be tight.
        if (reader.limit - reader.position <= 10) {
            return readLongSignedSlow(reader);
        }

        long v = reader.buffer[reader.position++];
        long accumulator = ((v & 0x40) == 0) ? 0l : 0xFFFFFFFFFFFFFF80l;

        while (v >= 0) {
            accumulator = (accumulator | v) << 7;
            v = reader.buffer[reader.position++];
        }

        return accumulator | (v & 0x7Fl);
    }

    private static long readLongSignedSlow(PrimitiveReader reader) {
        // slow path
        if (reader.position >= reader.limit) {
            fetch(1, reader);
        }
        int v = reader.buffer[reader.position++];
        long accumulator = ((v & 0x40) == 0) ? 0 : 0xFFFFFFFFFFFFFF80l;

        while (v >= 0) { // (v & 0x80)==0) {
            if (reader.position >= reader.limit) {
                fetch(1, reader);
            }
            accumulator = (accumulator | v) << 7;
            v = reader.buffer[reader.position++];
        }
        return accumulator | (v & 0x7F);
    }

    public static final long readLongUnsigned(PrimitiveReader reader) {
        return readLongUnsignedPrivate(reader);
    }

    private static long readLongUnsignedPrivate(PrimitiveReader reader) {
        if (reader.position > reader.limit - 10) {
            if (reader.position >= reader.limit) {
                fetch(1, reader);
            }
            byte v = reader.buffer[reader.position++];
            long accumulator;
            if (v >= 0) { // (v & 0x80)==0) {
                accumulator = v << 7;
            } else {
                return (v & 0x7F);
            }

            if (reader.position >= reader.limit) {
                fetch(1, reader);
            }
            v = reader.buffer[reader.position++];

            while (v >= 0) { // (v & 0x80)==0) {
                accumulator = (accumulator | v) << 7;

                if (reader.position >= reader.limit) {
                    fetch(1, reader);
                }
                v = reader.buffer[reader.position++];

            }
            return accumulator | (v & 0x7F);
        }
        byte[] buf = reader.buffer;

        byte v = buf[reader.position++];
        long accumulator;
        if (v >= 0) {// (v & 0x80)==0) {
            accumulator = v << 7;
        } else {
            return (v & 0x7F);
        }

        v = buf[reader.position++];
        while (v >= 0) {// (v & 0x80)==0) {
            accumulator = (accumulator | v) << 7;
            v = buf[reader.position++];
        }
        return accumulator | (v & 0x7F);
    }

    public static final int readIntegerSigned(PrimitiveReader reader) {
        return readIntegerSignedPrivate(reader);
    }

    private static int readIntegerSignedPrivate(PrimitiveReader reader) {
        if (reader.limit - reader.position <= 5) {
            return readIntegerSignedSlow(reader);
        }
        int p = reader.position;
        byte v = reader.buffer[p++];
        int accumulator = ((v & 0x40) == 0) ? 0 : 0xFFFFFF80;

        while (v >= 0) { // (v & 0x80)==0) {
            accumulator = (accumulator | v) << 7;
            v = reader.buffer[p++];
        }
        reader.position = p;
        return accumulator | (v & 0x7F);
    }

    private static int readIntegerSignedSlow(PrimitiveReader reader) {
        if (reader.position >= reader.limit) {
            fetch(1, reader);
        }
        byte v = reader.buffer[reader.position++];
        int accumulator = ((v & 0x40) == 0) ? 0 : 0xFFFFFF80;

        while (v >= 0) { // (v & 0x80)==0) {
            if (reader.position >= reader.limit) {
                fetch(1, reader);
            }
            accumulator = (accumulator | v) << 7;
            v = reader.buffer[reader.position++];
        }
        return accumulator | (v & 0x7F);
    }

    public static final int readIntegerUnsigned(PrimitiveReader reader) {

        return readIntegerUnsignedPrivate(reader);
    }

    private static int readIntegerUnsignedPrivate(PrimitiveReader reader) {//Invoked 100's of millions of times, must be tight.
        if (reader.limit - reader.position >= 5) {// not near end so go fast.
            byte v;
            return ((v = reader.buffer[reader.position++]) < 0) ? (v & 0x7F) : readIntegerUnsignedLarger(v, reader);
        } else {
            return readIntegerUnsignedSlow(reader);
        }
    }

    private static int readIntegerUnsignedLarger(byte t, PrimitiveReader reader) {
        byte v = reader.buffer[reader.position++];
        if (v < 0) {
            return (t << 7) | (v & 0x7F);
        } else {
            int accumulator = ((t << 7) | v) << 7;
            while ((v = reader.buffer[reader.position++]) >= 0) {
                accumulator = (accumulator | v) << 7;
            }
            return accumulator | (v & 0x7F);
        }
    }

    private static int readIntegerUnsignedSlow(PrimitiveReader reader) {
        if (reader.position >= reader.limit) {
            fetch(1, reader);
        }
        byte v = reader.buffer[reader.position++];
        int accumulator;
        if (v >= 0) { // (v & 0x80)==0) {
            accumulator = v << 7;
        } else {
            return (v & 0x7F);
        }

        if (reader.position >= reader.limit) {
            fetch(1, reader);
        }
        v = reader.buffer[reader.position++];

        while (v >= 0) { // (v & 0x80)==0) {
            accumulator = (accumulator | v) << 7;
            if (reader.position >= reader.limit) {
                fetch(1, reader);
            }
            v = reader.buffer[reader.position++];
        }
        return accumulator | (v & 0x7F);
    }

    public Appendable readTextASCII(Appendable target) {
        if (limit - position < 2) {
            fetch(2, this);
        }

        byte v = buffer[position];

        if (0 == v) {
            v = buffer[position + 1];
            if (0x80 != (v & 0xFF)) {
                throw new UnsupportedOperationException();
            }
            // nothing to change in the target
            position += 2;
        } else {
            // must use count because the base of position will be in motion.
            // however the position can not be incremented or fetch may drop
            // data.

            while (buffer[position] >= 0) {
                try {
                    target.append((char) (buffer[position]));
                } catch (IOException e) {
                    throw new FASTException(e);
                }
                position++;
                if (position >= limit) {
                    fetch(1, this); // CAUTION: may change value of position
                }
            }
            try {
                target.append((char) (0x7F & buffer[position]));
            } catch (IOException e) {
                throw new FASTException(e);
            }

            position++;

        }
        return target;
    }

    public final int readTextASCII(char[] target, int targetOffset, int targetLimit) {

        // TODO: Z, speed up textASCII, by add fast copy by fetch of limit, then
        // return error when limit is reached? Do not call fetch on limit we do
        // not know that we need them.

        if (limit - position < 2) {
            fetch(2, this);
        }

        byte v = buffer[position];

        if (0 == v) {
            v = buffer[position + 1];
            if (0x80 != (v & 0xFF)) {
                throw new UnsupportedOperationException();
            }
            // nothing to change in the target
            position += 2;
            return 0; // zero length string
        } else {
            int countDown = targetLimit - targetOffset;
            // must use count because the base of position will be in motion.
            // however the position can not be incremented or fetch may drop
            // data.
            int idx = targetOffset;
            while (buffer[position] >= 0 && --countDown >= 0) {
                target[idx++] = (char) (buffer[position++]);
                if (position >= limit) {
                    fetch(1, this); // CAUTION: may change value of position
                }
            }
            if (--countDown >= 0) {
                target[idx++] = (char) (0x7F & buffer[position++]);
                return idx - targetOffset;// length of string
            } else {
                return targetOffset - idx;// neg length of string if hit max
            }
        }
    }

    public final int readTextASCII2(char[] target, int targetOffset, int targetLimit) {

        int countDown = targetLimit - targetOffset;
        if (limit - position >= countDown) {
            // System.err.println("fast");
            // must use count because the base of position will be in motion.
            // however the position can not be incremented or fetch may drop
            // data.
            int idx = targetOffset;
            while (buffer[position] >= 0 && --countDown >= 0) {
                target[idx++] = (char) (buffer[position++]);
            }
            if (--countDown >= 0) {
                target[idx++] = (char) (0x7F & buffer[position++]);
                return idx - targetOffset;// length of string
            } else {
                return targetOffset - idx;// neg length of string if hit max
            }
        } else {
            return readAsciiText2Slow(target, targetOffset, countDown);
        }
    }

    private int readAsciiText2Slow(char[] target, int targetOffset, int countDown) {
        if (limit - position < 2) {
            fetch(2, this);
        }

        // must use count because the base of position will be in motion.
        // however the position can not be incremented or fetch may drop data.
        int idx = targetOffset;
        while (buffer[position] >= 0 && --countDown >= 0) {
            target[idx++] = (char) (buffer[position++]);
            if (position >= limit) {
                fetch(1, this); // CAUTION: may change value of position
            }
        }
        if (--countDown >= 0) {
            target[idx++] = (char) (0x7F & buffer[position++]);
            return idx - targetOffset;// length of string
        } else {
            return targetOffset - idx;// neg length of string if hit max
        }
    }

    // keep calling while byte is >=0
    public final byte readTextASCIIByte() {
        if (position >= limit) {
            fetch(1, this); // CAUTION: may change value of position
        }
        return buffer[position++];
    }

    public Appendable readTextUTF8(int charCount, Appendable target) {

        while (--charCount >= 0) {
            if (position >= limit) {
                fetch(1, this); // CAUTION: may change value of position
            }
            byte b = buffer[position++];
            if (b >= 0) {
                // code point 7
                try {
                    target.append((char) b);
                } catch (IOException e) {
                    throw new FASTException(e);
                }
            } else {
                decodeUTF8(target, b);
            }
        }
        return target;
    }

    public final void readSkipByStop() {
        if (position >= limit) {
            fetch(1, this);
        }
        while (buffer[position++] >= 0) {
            if (position >= limit) {
                fetch(1, this);
            }
        }
    }

    public final void readSkipByLengthByt(int len) {
        if (limit - position < len) {
            fetch(len, this);
        }
        position += len;
    }

    public final void readSkipByLengthUTF(int len) {
        // len is units of utf-8 chars so we must check the
        // code points for each before fetching and jumping.
        // no validation at all because we are not building a string.
        while (--len >= 0) {
            if (position >= limit) {
                fetch(1, this);
            }
            byte b = buffer[position++];
            if (b < 0) {
                // longer pattern than 1 byte
                if (0 != (b & 0x20)) {
                    // longer pattern than 2 bytes
                    if (0 != (b & 0x10)) {
                        // longer pattern than 3 bytes
                        if (0 != (b & 0x08)) {
                            // longer pattern than 4 bytes
                            if (0 != (b & 0x04)) {
                                // longer pattern than 5 bytes
                                if (position >= limit) {
                                    fetch(5, this);
                                }
                                position += 5;
                            } else {
                                if (position >= limit) {
                                    fetch(4, this);
                                }
                                position += 4;
                            }
                        } else {
                            if (position >= limit) {
                                fetch(3, this);
                            }
                            position += 3;
                        }
                    } else {
                        if (position >= limit) {
                            fetch(2, this);
                        }
                        position += 2;
                    }
                } else {
                    if (position >= limit) {
                        fetch(1, this);
                    }
                    position++;
                }
            }
        }
    }

    public final void readTextUTF8(char[] target, int offset, int charCount) {
        // System.err.println("B");
        byte b;
        if (limit - position >= charCount << 3) { // if bigger than the text
                                                  // could be then use this
                                                  // shortcut
            // fast
            while (--charCount >= 0) {
                if ((b = buffer[position++]) >= 0) {
                    // code point 7
                    target[offset++] = (char) b;
                } else {
                    decodeUTF8Fast(target, offset++, b);// untested?? why
                }
            }
        } else {
            while (--charCount >= 0) {
                if (position >= limit) {
                    fetch(1, this); // CAUTION: may change value of position
                }
                if ((b = buffer[position++]) >= 0) {
                    // code point 7
                    target[offset++] = (char) b;
                } else {
                    decodeUTF8(target, offset++, b);
                }
            }
        }
    }

    // convert single char that is not the simple case
    private void decodeUTF8(Appendable target, byte b) {
        byte[] source = buffer;

        int result;
        if (((byte) (0xFF & (b << 2))) >= 0) {
            if ((b & 0x40) == 0) {
                try {
                    target.append((char) 0xFFFD); // Bad data replacement char
                } catch (IOException e) {
                    throw new FASTException(e);
                }
                if (position >= limit) {
                    fetch(1, this); // CAUTION: may change value of position
                }
                ++position;
                return;
            }
            // code point 11
            result = (b & 0x1F);
        } else {
            /*
             * //longer pattern than 1 byte if (0!=(b&0x20)) { //longer pattern
             * than 2 bytes if (0!=(b&0x10)) { //longer pattern than 3 bytes if
             * (0!=(b&0x08)) { //longer pattern than 4 bytes if (0!=(b&0x04)) {
             */

            if (0 != (b & 0x20)) {
                // if (((byte) (0xFF&(b<<3)) )>=0) { //TODO: T, Need UTF8 test
                // and then these would be faster/simpler by factoring out the
                // constant in this comparison.
                // code point 16
                result = (b & 0x0F);
            } else {
                if (0 != (b & 0x10)) {
                    // if (((byte)(0xFF&(b<<4)))>=0) {
                    // code point 21
                    if (true)
                        throw new UnsupportedOperationException("this is not getting tested!");
                    result = (b & 0x07);
                } else {
                    if (((byte) (0xFF & (b << 5))) >= 0) {
                        // code point 26
                        result = (b & 0x03);
                    } else {
                        if (((byte) (0xFF & (b << 6))) >= 0) {
                            // code point 31
                            result = (b & 0x01);
                        } else {
                            // System.err.println("odd byte :"+Integer.toBinaryString(b)+" at pos "+(offset-1));
                            // the high bit should never be set
                            try {
                                target.append((char) 0xFFFD); // Bad data
                                                              // replacement
                                                              // char
                            } catch (IOException e) {
                                throw new FASTException(e);
                            }
                            if (limit - position < 5) {
                                fetch(5, this);
                            }
                            position += 5;
                            return;
                        }

                        if ((source[position] & 0xC0) != 0x80) {
                            try {
                                target.append((char) 0xFFFD); // Bad data
                                                              // replacement
                                                              // char
                            } catch (IOException e) {
                                throw new FASTException(e);
                            }
                            if (limit - position < 5) {
                                fetch(5, this);
                            }
                            position += 5;
                            return;
                        }
                        if (position >= limit) {
                            fetch(1, this); // CAUTION: may change value of position
                        }
                        result = (result << 6) | (source[position++] & 0x3F);
                    }
                    if ((source[position] & 0xC0) != 0x80) {
                        try {
                            target.append((char) 0xFFFD); // Bad data
                                                          // replacement char
                        } catch (IOException e) {
                            throw new FASTException(e);
                        }
                        if (limit - position < 4) {
                            fetch(4, this);
                        }
                        position += 4;
                        return;
                    }
                    if (position >= limit) {
                        fetch(1, this); // CAUTION: may change value of position
                    }
                    result = (result << 6) | (source[position++] & 0x3F);
                }
                if ((source[position] & 0xC0) != 0x80) {
                    try {
                        target.append((char) 0xFFFD); // Bad data replacement
                                                      // char
                    } catch (IOException e) {
                        throw new FASTException(e);
                    }
                    if (limit - position < 3) {
                        fetch(3, this);
                    }
                    position += 3;
                    return;
                }
                if (position >= limit) {
                    fetch(1, this); // CAUTION: may change value of position
                }
                result = (result << 6) | (source[position++] & 0x3F);
            }
            if ((source[position] & 0xC0) != 0x80) {
                try {
                    target.append((char) 0xFFFD); // Bad data replacement char
                } catch (IOException e) {
                    throw new FASTException(e);
                }
                if (limit - position < 2) {
                    fetch(2, this);
                }
                position += 2;
                return;
            }
            if (position >= limit) {
                fetch(1, this); // CAUTION: may change value of position
            }
            result = (result << 6) | (source[position++] & 0x3F);
        }
        if ((source[position] & 0xC0) != 0x80) {
            try {
                target.append((char) 0xFFFD); // Bad data replacement char
            } catch (IOException e) {
                throw new FASTException(e);
            }
            if (position >= limit) {
                fetch(1, this); // CAUTION: may change value of position
            }
            position += 1;
            return;
        }
        try {
            if (position >= limit) {
                fetch(1, this); // CAUTION: may change value of position
            }
            target.append((char) ((result << 6) | (source[position++] & 0x3F)));
        } catch (IOException e) {
            throw new FASTException(e);
        }
    }

    // convert single char that is not the simple case
    private void decodeUTF8(char[] target, int targetIdx, byte b) {

        byte[] source = buffer;

        int result;
        if (((byte) (0xFF & (b << 2))) >= 0) {
            if ((b & 0x40) == 0) {
                target[targetIdx] = 0xFFFD; // Bad data replacement char
                if (position >= limit) {
                    fetch(1, this); // CAUTION: may change value of position
                }
                ++position;
                return;
            }
            // code point 11
            result = (b & 0x1F);
        } else {
            if (((byte) (0xFF & (b << 3))) >= 0) {
                // code point 16
                result = (b & 0x0F);
            } else {
                if (((byte) (0xFF & (b << 4))) >= 0) {
                    // code point 21
                    result = (b & 0x07);
                } else {
                    if (((byte) (0xFF & (b << 5))) >= 0) {
                        // code point 26
                        result = (b & 0x03);
                    } else {
                        if (((byte) (0xFF & (b << 6))) >= 0) {
                            // code point 31
                            result = (b & 0x01);
                        } else {
                            // System.err.println("odd byte :"+Integer.toBinaryString(b)+" at pos "+(offset-1));
                            // the high bit should never be set
                            target[targetIdx] = 0xFFFD; // Bad data replacement
                                                        // char
                            if (limit - position < 5) {
                                fetch(5, this);
                            }
                            position += 5;
                            return;
                        }

                        if ((source[position] & 0xC0) != 0x80) {
                            target[targetIdx] = 0xFFFD; // Bad data replacement
                                                        // char
                            if (limit - position < 5) {
                                fetch(5, this);
                            }
                            position += 5;
                            return;
                        }
                        if (position >= limit) {
                            fetch(1, this); // CAUTION: may change value of position
                        }
                        result = (result << 6) | (source[position++] & 0x3F);
                    }
                    if ((source[position] & 0xC0) != 0x80) {
                        target[targetIdx] = 0xFFFD; // Bad data replacement char
                        if (limit - position < 4) {
                            fetch(4, this);
                        }
                        position += 4;
                        return;
                    }
                    if (position >= limit) {
                        fetch(1, this); // CAUTION: may change value of position
                    }
                    result = (result << 6) | (source[position++] & 0x3F);
                }
                if ((source[position] & 0xC0) != 0x80) {
                    target[targetIdx] = 0xFFFD; // Bad data replacement char
                    if (limit - position < 3) {
                        fetch(3, this);
                    }
                    position += 3;
                    return;
                }
                if (position >= limit) {
                    fetch(1, this); // CAUTION: may change value of position
                }
                result = (result << 6) | (source[position++] & 0x3F);
            }
            if ((source[position] & 0xC0) != 0x80) {
                target[targetIdx] = 0xFFFD; // Bad data replacement char
                if (limit - position < 2) {
                    fetch(2, this);
                }
                position += 2;
                return;
            }
            if (position >= limit) {
                fetch(1, this); // CAUTION: may change value of position
            }
            result = (result << 6) | (source[position++] & 0x3F);
        }
        if ((source[position] & 0xC0) != 0x80) {
            target[targetIdx] = 0xFFFD; // Bad data replacement char
            if (position >= limit) {
                fetch(1, this); // CAUTION: may change value of position
            }
            position += 1;
            return;
        }
        if (position >= limit) {
            fetch(1, this); // CAUTION: may change value of position
        }
        target[targetIdx] = (char) ((result << 6) | (source[position++] & 0x3F));
    }

    private void decodeUTF8Fast(char[] target, int targetIdx, byte b) {

        byte[] source = buffer;

        int result;
        if (((byte) (0xFF & (b << 2))) >= 0) {
            if ((b & 0x40) == 0) {
                target[targetIdx] = 0xFFFD; // Bad data replacement char
                ++position;
                return;
            }
            // code point 11
            result = (b & 0x1F);
        } else {
            if (((byte) (0xFF & (b << 3))) >= 0) {
                // code point 16
                result = (b & 0x0F);
            } else {
                if (((byte) (0xFF & (b << 4))) >= 0) {
                    // code point 21
                    result = (b & 0x07);
                } else {
                    if (((byte) (0xFF & (b << 5))) >= 0) {
                        // code point 26
                        result = (b & 0x03);
                    } else {
                        if (((byte) (0xFF & (b << 6))) >= 0) {
                            // code point 31
                            result = (b & 0x01);
                        } else {
                            // System.err.println("odd byte :"+Integer.toBinaryString(b)+" at pos "+(offset-1));
                            // the high bit should never be set
                            target[targetIdx] = 0xFFFD; // Bad data replacement
                                                        // char
                            position += 5;
                            return;
                        }

                        if ((source[position] & 0xC0) != 0x80) {
                            target[targetIdx] = 0xFFFD; // Bad data replacement
                                                        // char
                            position += 5;
                            return;
                        }
                        result = (result << 6) | (source[position++] & 0x3F);
                    }
                    if ((source[position] & 0xC0) != 0x80) {
                        target[targetIdx] = 0xFFFD; // Bad data replacement char
                        position += 4;
                        return;
                    }
                    result = (result << 6) | (source[position++] & 0x3F);
                }
                if ((source[position] & 0xC0) != 0x80) {
                    target[targetIdx] = 0xFFFD; // Bad data replacement char
                    position += 3;
                    return;
                }
                result = (result << 6) | (source[position++] & 0x3F);
            }
            if ((source[position] & 0xC0) != 0x80) {
                target[targetIdx] = 0xFFFD; // Bad data replacement char
                position += 2;
                return;
            }
            result = (result << 6) | (source[position++] & 0x3F);
        }
        if ((source[position] & 0xC0) != 0x80) {
            target[targetIdx] = 0xFFFD; // Bad data replacement char
            position += 1;
            return;
        }
        target[targetIdx] = (char) ((result << 6) | (source[position++] & 0x3F));
    }

    public static final boolean isEOF(PrimitiveReader reader) {
        if (reader.limit != reader.position) {
            return false;
        }
        fetch(0, reader);
        return reader.limit != reader.position ? false : reader.input.isEOF();
    }

    // ///////////////////////////////
    // Dictionary specific operations
    // ///////////////////////////////

    public static final int readIntegerUnsignedOptional(int constAbsent, PrimitiveReader reader) {
        int value = readIntegerUnsignedPrivate(reader);
        return value == 0 ? constAbsent : value - 1;
    }

    public static final int readIntegerSignedConstantOptional(int constAbsent, int constConst, PrimitiveReader reader) {
        return (popPMapBit(reader) == 0 ? constAbsent : constConst);
    }

    public static final int readIntegerUnsignedConstantOptional(int constAbsent, int constConst, PrimitiveReader reader) {
        return (popPMapBit(reader) == 0 ? constAbsent : constConst);
    }

    public static final int readIntegerUnsignedCopy(int target, int source, int[] dictionary, PrimitiveReader reader) {
        return (popPMapBit(reader) == 0 ? dictionary[source]
                : (dictionary[target] = readIntegerUnsignedPrivate(reader)));
    }

    public static final int readIntegerUnsignedDefault(int constDefault, PrimitiveReader reader) {
        return (popPMapBit(reader) == 0 ? constDefault : readIntegerUnsignedPrivate(reader));
    }

    public static final int readIntegerUnsignedDefaultOptional(int constDefault, int constAbsent, PrimitiveReader reader) {
        int value;
        return (popPMapBit(reader) == 0) ? constDefault
                : (value = readIntegerUnsignedPrivate(reader)) == 0 ? constAbsent : value - 1;
    }

    public static final int readIntegerUnsignedIncrement(int target, int source, int[] dictionary, PrimitiveReader reader) {
        return (popPMapBit(reader) == 0 ? (dictionary[target] = dictionary[source] + 1)
                : (dictionary[target] = readIntegerUnsignedPrivate(reader)));
    }

    public static final int readIntegerUnsignedIncrementOptional(int target, int source, int[] dictionary, int constAbsent, PrimitiveReader reader) {

        if (popPMapBit(reader) == 0) {
            return (dictionary[target] == 0 ? constAbsent : (dictionary[target] = dictionary[source] + 1));
        } else {
            int value;
            if ((value = readIntegerUnsignedPrivate(reader)) == 0) {
                dictionary[target] = 0;
                return constAbsent;
            } else {
                return (dictionary[target] = value) - 1;
            }
        }
    }

    public static final int readIntegerSignedOptional(int constAbsent, PrimitiveReader reader) {
        int value = readIntegerSignedPrivate(reader);
        return value == 0 ? constAbsent : (value > 0 ? value - 1 : value);
    }

    public static final int readIntegerSignedCopy(int target, int source, int[] dictionary, PrimitiveReader reader) {
        return (popPMapBit(reader) == 0 ? dictionary[source]
                : (dictionary[target] = readIntegerSignedPrivate(reader)));
    }

    public static final int readIntegerSignedDelta(int target, int source, int[] dictionary, PrimitiveReader reader) {
        // Delta opp never uses PMAP
        return (dictionary[target] = (int) (dictionary[source] + readLongSignedPrivate(reader)));
    }

    public static final int readIntegerSignedDeltaOptional(int target, int source, int[] dictionary, int constAbsent, PrimitiveReader reader) {
        // Delta opp never uses PMAP
        long value = readLongSignedPrivate(reader);
        if (0 == value) {
            dictionary[target] = 0;// set to absent
            return constAbsent;
        } else {
            return dictionary[target] = (int) (dictionary[source] + (value > 0 ? value - 1 : value));

        }
    }

    public static final int readIntegerSignedDefault(int constDefault, PrimitiveReader reader) {
        return (popPMapBit(reader) == 0 ? constDefault : readIntegerSignedPrivate(reader));
    }

    public static final int readIntegerSignedDefaultOptional(int constDefault, int constAbsent, PrimitiveReader reader) {
        if (popPMapBit(reader) == 0) {
            return constDefault;
        } else {
            int value = readIntegerSignedPrivate(reader);
            return value == 0 ? constAbsent : (value > 0 ? value - 1 : value);
        }
    }

    public static final int readIntegerSignedIncrement(int target, int source, int[] dictionary, PrimitiveReader reader) {
        return (popPMapBit(reader) == 0 ? (dictionary[target] = dictionary[source] + 1)
                : (dictionary[target] = readIntegerSignedPrivate(reader)));
    }

    public static final int readIntegerSignedIncrementOptional(int target, int source, int[] dictionary, int constAbsent, PrimitiveReader reader) {

        if (popPMapBit(reader) == 0) {
            return (dictionary[target] == 0 ? constAbsent : (dictionary[target] = dictionary[source] + 1));
        } else {
            int value;
            if ((value = readIntegerSignedPrivate(reader)) == 0) {
                dictionary[target] = 0;
                return constAbsent;
            } else {
                return (dictionary[target] = value) - 1;
            }
        }
    }

    // For the Long values

    public static final long readLongUnsignedCopy(int target, int source, long[] dictionary, PrimitiveReader reader) {
        return (popPMapBit(reader) == 0 ? dictionary[source]
                : (dictionary[target] = readLongUnsignedPrivate(reader)));
    }

    public static final long readLongUnsignedDeltaOptional(int target, int source, long[] dictionary, long constAbsent, PrimitiveReader reader) {
        // Delta opp never uses PMAP
        long value = readLongSignedPrivate(reader);
        if (0 == value) {
            dictionary[target] = 0;// set to absent
            return constAbsent;
        } else {
            return dictionary[target] = (dictionary[source] + (value > 0 ? value - 1 : value));

        }
    }

    public static final long readLongUnsignedDefault(long constDefault, PrimitiveReader reader) {
        return (popPMapBit(reader) == 0 ? constDefault : readLongUnsignedPrivate(reader));
    }

    public static final long readLongUnsignedDefaultOptional(long constDefault, long constAbsent, PrimitiveReader reader) {
        if (popPMapBit(reader) == 0) {
            return constDefault;
        } else {
            long value = readLongUnsignedPrivate(reader);
            return value == 0 ? constAbsent : value - 1;
        }
    }

    public static final long readLongUnsignedIncrement(int target, int source, long[] dictionary, PrimitiveReader reader) {
        return (popPMapBit(reader) == 0 ? (dictionary[target] = dictionary[source] + 1)
                : (dictionary[target] = readLongUnsignedPrivate(reader)));
    }

    public static final long readLongUnsignedIncrementOptional(int target, int source, long[] dictionary, long constAbsent, PrimitiveReader reader) {

        if (popPMapBit(reader) == 0) {
            return (dictionary[target] == 0 ? constAbsent : (dictionary[target] = dictionary[source] + 1));
        } else {
            long value;
            if ((value = readLongUnsignedPrivate(reader)) == 0) {
                dictionary[target] = 0;
                return constAbsent;
            } else {
                return (dictionary[target] = value) - 1;
            }
        }
    }

    public static final long readLongSignedCopy(int target, int source, long[] dictionary, PrimitiveReader reader) {
        return (popPMapBit(reader) == 0 ? dictionary[source]
                : (dictionary[target] = readLongSignedPrivate(reader)));
    }

    public static final long readLongSignedDeltaOptional(int target, int source, long[] dictionary, long constAbsent, PrimitiveReader reader) {
        // Delta opp never uses PMAP
        long value = readLongSignedPrivate(reader);
        if (0 == value) {
            dictionary[target] = 0;// set to absent
            return constAbsent;
        } else {
            return dictionary[target] = (dictionary[source] + (value > 0 ? value - 1 : value));

        }
    }

    public static final long readLongSignedDefault(long constDefault, PrimitiveReader reader) {
        return (popPMapBit(reader) == 0 ? constDefault : readLongSignedPrivate(reader));
    }

    public static final long readLongSignedDefaultOptional(long constDefault, long constAbsent, PrimitiveReader reader) {
        if (popPMapBit(reader) == 0) {
            return constDefault;
        } else {
            long value = readLongSignedPrivate(reader);
            return value == 0 ? constAbsent : (value > 0 ? value - 1 : value);
        }
    }

    public static final long readLongSignedIncrement(int target, int source, long[] dictionary, PrimitiveReader reader) {
        return (popPMapBit(reader) == 0 ? (dictionary[target] = dictionary[source] + 1)
                : (dictionary[target] = readLongSignedPrivate(reader)));
    }

    public static final long readLongSignedIncrementOptional(int target, int source, long[] dictionary, long constAbsent, PrimitiveReader reader) {

        if (popPMapBit(reader) == 0) {
            return (dictionary[target] == 0 ? constAbsent : (dictionary[target] = dictionary[source] + 1));
        } else {
            long value;
            if ((value = readLongSignedPrivate(reader)) == 0) {
                dictionary[target] = 0;
                return constAbsent;
            } else {
                return (dictionary[target] = value) - 1;
            }
        }
    }

    // //////////////
    // /////////

    public static final int openMessage(int pmapMaxSize, PrimitiveReader reader) {
        openPMap(pmapMaxSize, reader);
        // return template id or unknown
        return (0 != popPMapBit(reader)) ? readIntegerUnsignedPrivate(reader) : -1;// template Id

    }

}
