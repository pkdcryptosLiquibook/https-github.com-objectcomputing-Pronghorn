package com.ociweb.jfast.stream;

public class RingBuffers {
    
    private FASTRingBuffer[] buffers;
    private FASTRingBuffer[] uniqueBuffers;
    
    
    public RingBuffers(FASTRingBuffer[] buffers) {
        this.buffers = buffers;
        //Many of these buffers are duplicates because they are used by multiple script indexes.
        //Consolidate all the buffers into a single array
        
        //count unique buffers
        int count = 0;
        int i = buffers.length;
        while (--i>=0) {            
            int j = i;
            while (--j>=0) {
                if (buffers[i]==buffers[j]) {
                    break;
                }
            }
            count += (j>>>31); //add the high bit because this is negative              
        }
        //System.err.println("unique buffers:"+count);
              
        
        this.uniqueBuffers = new FASTRingBuffer[count];
        count = 0;
        i = buffers.length;
        while (--i>=0) {            
            int j = i;
            while (--j>=0) {
                if (buffers[i]==buffers[j]) {
                    break;
                }
            }
            uniqueBuffers[count] = buffers[i];
            count += (j>>>31); //add the high bit because this is negative              
        }        
                
    }
    
    //package protected
    FASTRingBuffer[] rawArray() {
        return buffers;
    }

    public static void reset(RingBuffers ringBuffers) {
        //reset all ringbuffers
        int j = ringBuffers.buffers.length;
        while (--j>=0) {
            ringBuffers.buffers[j].reset();
        }
    }

    public static FASTRingBuffer get(RingBuffers ringBuffers, int idx) {
        return ringBuffers.buffers[idx];
    }
    
    public static FASTRingBuffer[] buffers(RingBuffers ringBuffers) {
        return ringBuffers.uniqueBuffers;
    }
    
}