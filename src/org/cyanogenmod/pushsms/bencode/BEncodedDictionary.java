package org.cyanogenmod.pushsms.bencode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map.Entry;
import java.util.Set;

public class BEncodedDictionary {
    public static BEncodedDictionary parseDictionary(ByteBuffer b) {
        BEncodedDictionary ret = new BEncodedDictionary();
        ensureStart(b, 'd');
        while (peek(b) != 'e') {
            String key = parseString(b);
            ret.dict.put(key, parse(b));
        }
        ensureEnd(b);
        return ret;
    }
    
    private static Object parse(ByteBuffer b) {
        switch (peek(b)) {
        case 'd':
            return parseDictionary(b);
        case 'i':
            return parseInt(b);
        case 'l':
            return parseList(b);
        case '0':
        case '1':
        case '2':
        case '3':
        case '4':
        case '5':
        case '6':
        case '7':
        case '8':
        case '9':
            return parseBytes(b);
        default:
            throw new IllegalArgumentException("bad format");
        }
    }
    
    private static char peek(ByteBuffer b) {
        char ret = (char)b.get();
        b.position(b.position() - 1);
        return ret;
    }
    
    public static void ensureStart(ByteBuffer b, char start) {
        if (b.get() != start)
            throw new IllegalArgumentException("bad format");
    }
    
    public static void ensureEnd(ByteBuffer b) {
        ensureStart(b, 'e');
    }
    
    private static int parseIntInternal(ByteBuffer b, char delim) {
        String val = "";
        char c;
        while (delim != (c = (char)b.get())) {
            switch (c) {
            case '0':
            case '1':
            case '2':
            case '3':
            case '4':
            case '5':
            case '6':
            case '7':
            case '8':
            case '9':
                val += c;
                break;
            default:
                throw new IllegalArgumentException("bad format");
            }
        }
        return Integer.parseInt(val);
    }

    public static int parseInt(ByteBuffer b) {
        ensureStart(b, 'i');
        int ret = parseIntInternal(b, 'e');
        return ret;
    }
    
    public static BEncodedList parseList(ByteBuffer b) {
        ensureStart(b, 'l');

        BEncodedList ret = new BEncodedList();
        while (peek(b) != 'e') {
            ret.add(parse(b));
        }
        ensureEnd(b);
        return ret;
    }
    
    public static byte[] parseBytes(ByteBuffer b) {
        int len = parseIntInternal(b, ':');
        byte[] buf = new byte[len];
        b.get(buf);
        return buf;
    }
    
    public static String parseString(ByteBuffer b) {
        return new String(parseBytes(b));
    }

    HashMap<String, Object> dict = new HashMap<String, Object>();

    public Set<String> keySet() {
        return dict.keySet();
    }
    
    static String escape(Object o) {
        if (o instanceof byte[]) {
            return "\"" + new String((byte[])o) + "\""; 
        }
        return o.toString();
    }
    
    @Override
    public String toString() {
        StringBuilder b = new StringBuilder();
        b.append('{');
        int count = 0;
        for (Entry<String, Object> entry: dict.entrySet()) {
            b.append('"');
            b.append(entry.getKey());
            b.append("\":");
            b.append(escape(entry.getValue()));
            if (++count < dict.size())
                b.append(',');
        }
        b.append('}');
        return b.toString();
    }

    public void put(String key, Object value) {
        if (value instanceof String)
            value = value.toString().getBytes();
        dict.put(key, value);
    }

    public static void write(ByteArrayOutputStream bout, Object o) throws IOException {
        if (o instanceof String) {
            o = o.toString().getBytes();
        }
        if (o instanceof byte[]) {
            byte[] bytes = (byte[])o;
            bout.write(String.valueOf(bytes.length).getBytes());
            bout.write(':');
            bout.write(bytes);
        }
        else if (o instanceof Integer) {
            bout.write('i');
            bout.write(o.toString().getBytes());
            bout.write('e');
        }
        else if (o instanceof BEncodedList) {
            BEncodedList l = (BEncodedList)o;
            bout.write('l');
            for (Object i: l) {
                write(bout, i);
            }
            bout.write('e');
        }
        else if (o instanceof BEncodedDictionary) {
            bout.write('d');
            BEncodedDictionary dict = (BEncodedDictionary)o;
            for (Entry<String, Object> entry: dict.dict.entrySet()) {
                write(bout, entry.getKey());
                write(bout, entry.getValue());
            }
            bout.write('e');
        }
        else {
            throw new IllegalArgumentException("o");
        }
    }

    public byte[] toByteArray() {
        ByteArrayOutputStream bout = new ByteArrayOutputStream();
        try {
            write(bout, this);
            return bout.toByteArray();
        }
        catch (IOException e) {
            throw new IllegalArgumentException(e);
        }
    }

    public BEncodedDictionary getBEncodedDictionary(String key) {
        return (BEncodedDictionary)dict.get(key);
    }

    public int getInt(String key) {
        return (Integer)dict.get(key);
    }
    public String getString(String key) {
        byte[] bytes = getBytes(key);
        if (bytes == null)
            return null;
        return new String(bytes);
    }
    public byte[] getBytes(String key) {
        return (byte[])dict.get(key);
    }
    public BEncodedList getBEncodedList(String key) {
        return (BEncodedList)dict.get(key);
    }
}
