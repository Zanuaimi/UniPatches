package unipatch.overlaycore;

import java.io.File;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * In-process memory scanner for the Universal Overlay.
 *
 * Reads and writes the host app's own address space via /proc/self/maps and
 * /proc/self/mem. No root, no ptrace, no cross-process access: a process can
 * always read and write its own memory, so this only ever touches the app it
 * is patched into.
 *
 * The scanner operates inside the patched process and never attaches elsewhere.
 *
 * Scan modes mirror the familiar narrow-as-you-play loop: exact value, increased,
 * decreased, and unchanged since the previous scan. Values are grouped by their
 * current contents so repeated scans intersect rather than accumulate.
 */
public final class MemoryScanner {

    /** How a candidate must have changed between two scans. */
    public enum Mode {
        /** Value equals the search value. */
        EXACT,
        /** Value is greater than it was at the previous scan. */
        INCREASED,
        /** Value is less than it was at the previous scan. */
        DECREASED,
        /** Value did not change since the previous scan. */
        UNCHANGED
    }

    /** One readable/writable private mapping, page-aligned. */
    private static final class Region {
        final long start;
        final long end;

        Region(long start, long end) {
            this.start = start;
            this.end = end;
        }

        long size() { return end - start; }
    }

    /**
     * Per-region read cap. The Java heap on a large app runs to hundreds of MB
     * and scanning it as 4-byte words takes seconds. A mapping larger than the
     * cap is still scanned up to the cap instead of being skipped, so a value
     * in the first part of a large heap mapping remains reachable. Callers can
     * narrow with {@link #setRegionFilter} before committing to a full sweep.
     */
    private static final long DEFAULT_MAX_REGION_BYTES = 64L * 1024 * 1024;
    private static final long MAX_SCAN_BYTES = 128L * 1024 * 1024;

    static final int MAX_RESULTS = 20_000;

    private long maxRegionBytes = DEFAULT_MAX_REGION_BYTES;
    private String regionFilter = null;

    private final Map<Long, Integer> previous = new LinkedHashMap<>();
    private final Map<Long, Integer> current = new LinkedHashMap<>();
    private boolean hasPrevious = false;
    private boolean lastScanReadable = false;
    private boolean lastScanTruncated = false;

    /** Restricts scanning to mappings whose path contains {@code needle}. */
    public void setRegionFilter(String needle) {
        this.regionFilter = (needle == null || needle.isEmpty()) ? null : needle;
    }

    public void setMaxRegionBytes(long bytes) { this.maxRegionBytes = bytes; }

    /** Resets narrowing state so the next scan starts a fresh search. */
    public void reset() {
        previous.clear();
        current.clear();
        hasPrevious = false;
        lastScanReadable = false;
        lastScanTruncated = false;
    }

    public int resultCount() { return current.size(); }
    public boolean wasLastScanReadable() { return lastScanReadable; }
    /** True when the result set hit the byte budget or the result cap. */
    public boolean wasLastScanTruncated() { return lastScanTruncated; }

    public List<Long> resultAddresses(int limit) {
        List<Long> out = new ArrayList<>();
        for (Long address : current.keySet()) {
            if (out.size() >= limit) break;
            out.add(address);
        }
        return out;
    }

    public int valueAt(long address) {
        Integer v = current.get(address);
        if (v != null) return v;
        byte[] buf = new byte[4];
        if (read(address, buf) == 4) {
            return littleEndianInt(buf);
        }
        return 0;
    }

    /**
     * Runs a scan. The first call ignores {@code mode} and seeds the baseline;
     * later calls apply {@code mode} to narrow the previous result set.
     */
    public int scan(Mode mode, int searchValue) {
        if (!hasPrevious) {
            collectAll(searchValue);
            hasPrevious = true;
            return current.size();
        }

        Map<Long, Integer> next = new LinkedHashMap<>();
        lastScanReadable = false;
        try (RandomAccessFile mem = new RandomAccessFile(new File("/proc/self/mem"), "r")) {
            byte[] buf = new byte[4];
            for (Map.Entry<Long, Integer> entry : previous.entrySet()) {
                long address = entry.getKey();
                int oldValue = entry.getValue();
                try {
                    mem.seek(address);
                    mem.readFully(buf);
                } catch (Exception staleMapping) {
                    continue;
                }
                lastScanReadable = true;
                int newValue = littleEndianInt(buf);
                boolean keep;
                switch (mode) {
                    case EXACT:     keep = newValue == searchValue; break;
                    case INCREASED: keep = newValue > oldValue; break;
                    case DECREASED: keep = newValue < oldValue; break;
                    case UNCHANGED: keep = newValue == oldValue; break;
                    default:        keep = false;
                }
                if (keep && next.size() < MAX_RESULTS) next.put(address, newValue);
            }
        } catch (Exception unreadableMemory) {
            // Keep an empty result set if /proc/self/mem is unavailable.
        }

        previous.clear();
        previous.putAll(next);
        current.clear();
        current.putAll(next);
        return current.size();
    }

    private void collectAll(int searchValue) {
        previous.clear();
        current.clear();
        lastScanReadable = false;
        lastScanTruncated = false;
        long scanned = 0;
        for (Region region : parseMaps()) {
            if (scanned >= MAX_SCAN_BYTES || current.size() >= MAX_RESULTS) {
                lastScanTruncated = true;
                break;
            }
            long start = (region.start + 3L) & ~3L;
            long regionLimit = Math.min(region.end, region.start + Math.max(4L, maxRegionBytes));
            long end = Math.min(regionLimit & ~3L, start + (MAX_SCAN_BYTES - scanned));
            if (end <= start) {
                if (regionLimit < region.end) lastScanTruncated = true;
                continue;
            }
            if (end < region.end) lastScanTruncated = true;
            // Keep one descriptor open per mapping rather than opening /proc/self/mem
            // for every 4-byte value.
            try (RandomAccessFile mem = new RandomAccessFile(new File("/proc/self/mem"), "r")) {
                byte[] buf = new byte[64 * 1024];
                for (long address = start; address + 4 <= end && current.size() < MAX_RESULTS; ) {
                    int bytes = (int) Math.min(buf.length, end - address);
                    try {
                        mem.seek(address);
                        mem.readFully(buf, 0, bytes);
                        lastScanReadable = true;
                        for (int offset = 0; offset + 4 <= bytes; offset += 4) {
                            int value = littleEndianInt(buf, offset);
                            if (value == searchValue) {
                                long hit = address + offset;
                                current.put(hit, value);
                                previous.put(hit, value);
                                if (current.size() >= MAX_RESULTS) {
                                    lastScanTruncated = true;
                                    break;
                                }
                            }
                        }
                        address += bytes;
                    } catch (Exception unreadablePage) {
                        address = (address + 0x1000L) & ~0xfffL;
                    }
                }
            } catch (Exception unreadableRegion) {
                // A mapping can disappear while the app is running.
            }
            scanned += Math.max(0, end - start);
        }
    }

    /** Writes an int32 at {@code address}. Returns true when the write landed. */
    public boolean writeInt(long address, int value) {
        if (!current.containsKey(address)) return false;
        byte[] buf = new byte[4];
        buf[0] = (byte) (value & 0xff);
        buf[1] = (byte) ((value >>> 8) & 0xff);
        buf[2] = (byte) ((value >>> 16) & 0xff);
        buf[3] = (byte) ((value >>> 24) & 0xff);
        if (write(address, buf) != 4) return false;
        current.put(address, value);
        previous.put(address, value);
        return true;
    }

    /** Human-readable name for a result address, used by the overlay panel. */
    public String describe(long address) {
        String path = mappingPath(address);
        if (path == null || path.isEmpty()) return String.format("0x%X [anon]", address);
        return String.format("0x%X %s", address, path);
    }

    private String mappingPath(long address) {
        try (java.io.BufferedReader reader =
                     new java.io.BufferedReader(new java.io.FileReader("/proc/self/maps"))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.trim().split("\\s+", 6);
                if (parts.length < 6) continue;
                String[] range = parts[0].split("-");
                if (range.length != 2) continue;
                long start = parseAddress(range[0]);
                long end = parseAddress(range[1]);
                if (address < start || address >= end) continue;
                String path = parts[5];
                int slash = path.lastIndexOf('/');
                return slash >= 0 ? path.substring(slash + 1) : path;
            }
        } catch (Exception ignored) {
            // /proc/self/maps is not always readable; description is best-effort.
        }
        return null;
    }

    /** Parses /proc/self/maps into readable, writable, private mappings. */
    private List<Region> parseMaps() {
        List<Region> out = new ArrayList<>();
        try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.FileReader("/proc/self/maps"))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.trim().split("\\s+", 6);
                if (parts.length < 5) continue;
                String perms = parts[1];
                if (!isReadableWritablePrivate(perms)) continue;
                String[] range = parts[0].split("-");
                if (range.length != 2) continue;
                long start = parseAddress(range[0]);
                long end = parseAddress(range[1]);
                if (end <= start) continue;
                String name = parts.length > 5 ? parts[5] : "";
                if (regionFilter != null && !name.contains(regionFilter)) continue;
                out.add(new Region(start, end));
            }
        } catch (Exception error) {
            android.util.Log.w("UnipatchMemScan", "/proc/self/maps unreadable: " + error);
        }
        return out;
    }

    static boolean isReadableWritablePrivate(String perms) {
        return perms != null && perms.length() >= 4 && perms.charAt(0) == 'r'
                && perms.charAt(1) == 'w' && perms.charAt(3) == 'p';
    }

    /**
     * Parses a hexadecimal /proc/self/maps address.
     *
     * Long.parseUnsignedLong is API 26 and the extension supports API 23, so
     * this loop handles both 32-bit and 64-bit user-space addresses without a
     * newer platform API. User-space addresses are below 2^63, so a signed long
     * holds them exactly.
     */
    static long parseAddress(String hex) {
        if (hex == null || hex.isEmpty() || hex.length() > 16) {
            throw new NumberFormatException("invalid address: " + hex);
        }
        long value = 0L;
        for (int i = 0; i < hex.length(); i++) {
            int digit = Character.digit(hex.charAt(i), 16);
            if (digit < 0) throw new NumberFormatException("invalid address: " + hex);
            value = (value << 4) | digit;
        }
        return value;
    }

    private int read(long address, byte[] buf) {
        try (RandomAccessFile mem = new RandomAccessFile(new File("/proc/self/mem"), "r")) {
            mem.seek(address);
            mem.readFully(buf);
            return buf.length;
        } catch (Exception error) {
            return -1;
        }
    }

    private int write(long address, byte[] buf) {
        try (RandomAccessFile mem = new RandomAccessFile(new File("/proc/self/mem"), "rw")) {
            mem.seek(address);
            mem.write(buf);
            return buf.length;
        } catch (Exception error) {
            return -1;
        }
    }

    private static int littleEndianInt(byte[] b) {
        return littleEndianInt(b, 0);
    }

    private static int littleEndianInt(byte[] b, int offset) {
        return (b[offset] & 0xff)
                | ((b[offset + 1] & 0xff) << 8)
                | ((b[offset + 2] & 0xff) << 16)
                | ((b[offset + 3] & 0xff) << 24);
    }
}
