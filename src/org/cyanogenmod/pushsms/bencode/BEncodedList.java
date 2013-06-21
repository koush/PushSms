package org.cyanogenmod.pushsms.bencode;

import java.util.ArrayList;


public class BEncodedList extends ArrayList<Object> {
    public int getInt(int index) {
        return 0;
    }
    
    public String getString(int index) {
        return new String(getBytes(index));
    }
    
    public byte[] getBytes(int index) {
        return (byte[])get(index);
    }
    
    public BEncodedList getArray(int index) {
        return (BEncodedList)get(index);
    }
    
    public BEncodedDictionary getDictionary(int index) {
        return (BEncodedDictionary)get(index);
    }
    
    @Override
    public String toString() {
        StringBuilder b = new StringBuilder();
        b.append('[');
        int count = 0;
        for (Object o: this) {
            b.append(BEncodedDictionary.escape(o));
            if (++count < size())
                b.append(',');
        }
        b.append(']');
        return b.toString();
    }
}
