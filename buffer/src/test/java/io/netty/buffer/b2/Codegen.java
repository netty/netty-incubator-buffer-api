package io.netty.buffer.b2;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.Stream.Builder;

import static java.nio.charset.StandardCharsets.*;
import static java.nio.file.StandardOpenOption.*;

public final class Codegen {
    private static final Pattern ALL_DIGITS = Pattern.compile("^\\d+$");
    private static final Map<String, Supplier<Stream<String>>> REGION_GENERATORS = Map.of(
            "primitive accessors interface", Codegen::primitiveAccessorsInterface,
            "primitive accessors implementation", Codegen::primitiveAccessorsImplementation,
            "primitive accessors tests", Codegen::primitiveAccessorsTests);

    enum Type {
        BYTE("byte", "Byte", "Byte.BYTES", Byte.BYTES, false, "two's complement 8-bit", "int"),
        CHAR("char", "Char", "2", 2, true, "2-byte UTF-16", null),
        SHORT("short", "Short", "Short.BYTES", Short.BYTES, true, "two's complement 16-bit", "int"),
        MED("int", "Medium", "3", 3, true, "two's complement 24-bit", "int") {
            @Override
            public String load(boolean le, boolean unsigned) {
                String indent = "                    ";
                String tailPart = unsigned? ") & 0x" + "FF".repeat(actualSize) : "";
                if (le) {
                    return (unsigned? "(" : "") +
                           "getByteAtOffset_BE(seg, roff) & 0xFF |\n" +
                           indent +
                           "(getByteAtOffset_BE(seg, roff + 1) & 0xFF) << 8 |\n" +
                           indent +
                           "getByteAtOffset_BE(seg, roff + 2) << 16" +
                           tailPart;
                } else {
                    return (unsigned? "(" : "") +
                           "getByteAtOffset_BE(seg, roff) << 16 |\n" +
                           indent +
                           "(getByteAtOffset_BE(seg, roff + 1) & 0xFF) << 8 |\n" +
                           indent +
                           "getByteAtOffset_BE(seg, roff + 2) & 0xFF" +
                           tailPart;
                }
            }

            @Override
            public String store(boolean le, boolean unsigned) {
                String indent = "        ";
                if (le) {
                    return "setByteAtOffset_BE(seg, woff, (byte) (value & 0xFF));\n" +
                           indent +
                           "setByteAtOffset_BE(seg, woff + 1, (byte) (value >> 8 & 0xFF));\n" +
                           indent +
                           "setByteAtOffset_BE(seg, woff + 2, (byte) (value >> 16 & 0xFF))";
                } else {
                    return "setByteAtOffset_BE(seg, woff, (byte) (value >> 16));\n" +
                           indent +
                           "setByteAtOffset_BE(seg, woff + 1, (byte) (value >> 8 & 0xFF));\n" +
                           indent +
                           "setByteAtOffset_BE(seg, woff + 2, (byte) (value & 0xFF))";
                }
            }
        },
        INT("int", "Int", "Integer.BYTES", Integer.BYTES, true, "two's complement 32-bit", "long"),
        FLOAT("float", "Float", "Float.BYTES", Float.BYTES, true, "32-bit IEEE floating point", null),
        LONG("long", "Long", "Long.BYTES", Long.BYTES, true, "two's complement 64-bit", null),
        DOUBLE("double", "Double", "Double.BYTES", Double.BYTES, true, "64-bit IEEE floating point", null),
        ;

        protected final String type;
        protected final String title;
        protected final String size;
        protected final int actualSize;
        protected final boolean includeLE;
        protected final String extra;
        protected final String unsignedCarrier;

        Type(String type, String title, String size, int actualSize, boolean includeLE, String extra, String unsignedCarrier) {
            this.type = type;
            this.title = title;
            this.size = size;
            this.actualSize = actualSize;
            this.includeLE = includeLE;
            this.extra = extra;
            this.unsignedCarrier = unsignedCarrier;
        }

        public String title(boolean le, boolean unsigned) {
            return (unsigned? "Unsigned" + title : title) + (le? "LE" : "");
        }

        public String extraRead(boolean le, boolean unsigned) {
            return getExtra("read", le, unsigned);
        }

        public String extraWrite(boolean le, boolean unsigned) {
            return getExtra("written", le, unsigned);
        }

        private String getExtra(String op, boolean le, boolean unsigned) {
            return "The value is " + op + " using " +
                   (unsigned? "an unsigned " : "a ") +
                   extra +
                   " encoding, with " +
                   (le? "little" : "big") +
                   "-endian byte order.";
        }

        public String type(boolean unsigned) {
            return unsigned? unsignedCarrier : type;
        }

        public String load(boolean le, boolean unsigned) {
            boolean longCarrier = "long".equals(unsignedCarrier);
            boolean intCarrier = "int".equals(unsignedCarrier);
            return (unsigned && !longCarrier && !intCarrier? '(' + unsignedCarrier + ") (" : "") +
                   "get" +
                   title +
                   "AtOffset_" +
                   (le? "LE" : "BE") +
                   "(seg, roff)" +
                   (unsigned? " & 0x" + "FF".repeat(actualSize) +
                              (longCarrier? 'L' : intCarrier? "" : ')') : "");
        }

        public String store(boolean le, boolean unsigned) {
            boolean longCarrier = "long".equals(unsignedCarrier);
            return "set" +
                   title +
                   "AtOffset_" +
                   (le? "LE" : "BE") +
                   "(seg, woff, " +
                   (unsigned? '(' + type + ") (value & 0x" + "FF".repeat(actualSize) +
                              (longCarrier? "L)" : ")") : "value") +
                   ')';
        }

        public String realType(boolean unsigned) {
            return unsigned? "unsigned " + type : type;
        }
    }

    enum Template {
        INTERFACE {
            @Override
            public String relativeRead(Type type, boolean le, boolean unsigned, boolean read) {
                var tmpl = '\n' +
                       "    /**\n" +
                       "     * Get the %8$s value at the current {@link Buf#readerIndex()},\n" +
                       "     * and increases the reader offset by %3$s.\n" +
                       "     * %4$s\n" +
                       "     *\n" +
                       "     * @return The %8$s value at the current reader offset.\n" +
                       "     * @throws IndexOutOfBoundsException If {@link Buf#readableBytes} is less than %3$s.\n" +
                       "     */\n" +
                       "    %1$s read%2$s();";
                return format(tmpl, type, le, unsigned, read);
            }

            @Override
            public String offsetRead(Type type, boolean le, boolean unsigned, boolean read) {
                var tmpl = '\n' +
                       "    /**\n" +
                       "     * Get the %8$s value at the given reader offset.\n" +
                       "     * The {@link Buf#readerIndex()} is not modified.\n" +
                       "     * %4$s\n" +
                       "     *\n" +
                       "     * @param roff The read offset, an absolute index into this buffer, to read from.\n" +
                       "     * @return The %8$s value at the given offset.\n" +
                       "     * @throws IndexOutOfBoundsException if the given index is out of bounds of the buffer, that is, less than 0 or\n" +
                       "     *                                   greater than or equal to {@link Buf#capacity()} minus %3$s.\n" +
                       "     */\n" +
                       "    %1$s read%2$s(int roff);";
                return format(tmpl, type, le, unsigned, read);
            }

            @Override
            public String relativeWrite(Type type, boolean le, boolean unsigned, boolean read) {
                var tmpl = '\n' +
                       "    /**\n" +
                       "     * Set the given %8$s value at the current {@link Buf#writerIndex()},\n" +
                       "     * and increase the writer offset by %3$s.\n" +
                       "     * %4$s\n" +
                       "     *\n" +
                       "     * @param value The %1$s value to write.\n" +
                       "     * @return This Buf.\n" +
                       "     * @throws IndexOutOfBoundsException If {@link Buf#writableBytes} is less than %3$s.\n" +
                       "     */\n" +
                       "    Buf write%2$s(%1$s value);";
                return format(tmpl, type, le, unsigned, read);
            }

            @Override
            public String offsetWrite(Type type, boolean le, boolean unsigned, boolean read) {
                var tmpl = '\n' +
                       "    /**\n" +
                       "     * Set the given %8$s value at the given write offset. The {@link Buf#writerIndex()} is not modified.\n" +
                       "     * %4$s\n" +
                       "     *\n" +
                       "     * @param woff The write offset, an absolute index into this buffer to write to.\n" +
                       "     * @param value The %1$s value to write.\n" +
                       "     * @return This Buf.\n" +
                       "     * @throws IndexOutOfBoundsException if the given offset is out of bounds of the buffer, that is, less than 0 or\n" +
                       "     *                                   greater than or equal to {@link Buf#capacity()} minus %3$s.\n" +
                       "     */\n" +
                       "    Buf write%2$s(int woff, %1$s value);";
                return format(tmpl, type, le, unsigned, read);
            }
        },
        IMPLEMENTATION {
            @Override
            public String relativeRead(Type type, boolean le, boolean unsigned, boolean read) {
                var tmpl = '\n' +
                       "    @Override\n" +
                       "    public %1$s read%2$s() {\n" +
                       "        checkRead(roff, %5$s);\n" +
                       "        %1$s value = %6$s;\n" +
                       "        roff += %5$s;\n" +
                       "        return value;\n" +
                       "    }";
                return format(tmpl, type, le, unsigned, read);
            }

            @Override
            public String offsetRead(Type type, boolean le, boolean unsigned, boolean read) {
                var tmpl = '\n' +
                       "    @Override\n" +
                       "    public %1$s read%2$s(int roff) {\n" +
                       "        checkRead(roff, %5$s);\n" +
                       "        return %6$s;\n" +
                       "    }";
                return format(tmpl, type, le, unsigned, read);
            }

            @Override
            public String relativeWrite(Type type, boolean le, boolean unsigned, boolean read) {
                var tmpl = '\n' +
                       "    @Override\n" +
                       "    public Buf write%2$s(%1$s value) {\n" +
                       (type == Type.MED? "        checkWrite(woff, %5$s);\n" : "") +
                       "        %7$s;\n" +
                       "        woff += %5$s;\n" +
                       "        return this;\n" +
                       "    }";
                return format(tmpl, type, le, unsigned, read);
            }

            @Override
            public String offsetWrite(Type type, boolean le, boolean unsigned, boolean read) {
                var tmpl = '\n' +
                       "    @Override\n" +
                       "    public Buf write%2$s(int woff, %1$s value) {\n" +
                       (type == Type.MED? "        checkWrite(woff, %5$s);\n" : "") +
                       "        %7$s;\n" +
                       "        return this;\n" +
                       "    }";
                return format(tmpl, type, le, unsigned, read);
            }
        },
        TESTS {
            String testValue;
            String testValueByteOrder;
            int bytesAvailAfter;

            @Override
            public String relativeRead(Type type, boolean le, boolean unsigned, boolean read) {
                prepare(type);
                var tmpl = '\n' +
                           "    @Test\n" +
                           "    public void relativeReadOf%2$sMustNotBoundsCheckWhenReadOffsetAndSizeIsEqualToWriteOffset() {\n" +
                           "        assertEquals(0, buf.readableBytes());\n" +
                           "        assertEquals(Long.BYTES, buf.writableBytes());\n" +
                           "        %1$s value = " + testValue + ";\n" +
                           "        buf.write%2$s(value);\n" +
                           "        assertEquals(" + type.actualSize + ", buf.readableBytes());\n" +
                           "        assertEquals(" + bytesAvailAfter + ", buf.writableBytes());\n" +
                           "        assertEquals(value, buf.read%2$s());\n" +
                           "        assertEquals(0, buf.readableBytes());\n" +
                           "    }\n" +
                           '\n' +
                           "    @Test\n" +
                           "    public void relativeReadOf%2$sMustReadWith" + (le? "Little" : "Big") + "EndianByteOrder() {\n" +
                           "        assertEquals(0, buf.readableBytes());\n" +
                           "        assertEquals(Long.BYTES, buf.writableBytes());\n" +
                           "        %1$s value = " + testValue + ";\n" +
                           "        buf.write%2$s(value);\n" +
                           "        buf.writeByte(" + (le? type.actualSize - 1 : 0) + ", (byte) 0x10);\n" +
                           "        assertEquals(" + type.actualSize + ", buf.readableBytes());\n" +
                           "        assertEquals(" + bytesAvailAfter + ", buf.writableBytes());\n" +
                           "        assertEquals(" + testValueByteOrder + ", buf.read%2$s());\n" +
                           "        assertEquals(0, buf.readableBytes());\n" +
                           "    }\n" +
                           '\n' +
                           "    @Test\n" +
                           "    public void relativeReadOf%2$sMustBoundsCheckWhenReadOffsetAndSizeIsBeyondWriteOffset() {\n" +
                           "        assertEquals(0, buf.readableBytes());\n" +
                           "        assertEquals(Long.BYTES, buf.writableBytes());\n" +
                           "        %1$s value = " + testValue + ";\n" +
                           "        buf.write%2$s(value);\n" +
                           "        buf.readerIndex(1);\n" +
                           "        assertEquals(" + (type.actualSize - 1) + ", buf.readableBytes());\n" +
                           "        assertEquals(" + bytesAvailAfter + ", buf.writableBytes());\n" +
                           "        try {\n" +
                           "            buf.read%2$s();\n" +
                           "            fail(\"Expected a bounds check.\");\n" +
                           "        } catch (IndexOutOfBoundsException ignore) {\n" +
                           "            // Good.\n" +
                           "        }\n" +
                           "        assertEquals(" + (type.actualSize - 1) + ", buf.readableBytes());\n" +
                           "    }";
                return format(tmpl, type, le, unsigned, read);
            }

            @Override
            public String offsetRead(Type type, boolean le, boolean unsigned, boolean read) {
                prepare(type);
                var tmpl = '\n' +
                           "    @Test\n" +
                           "    public void offsettedReadOf%2$sMustBoundsCheckOnNegativeOffset() {\n" +
                           "        try {\n" +
                           "            buf.read%2$s(-1);\n" +
                           "            fail(\"Expected a bounds check.\");\n" +
                           "        } catch (IndexOutOfBoundsException ignore) {\n" +
                           "            // Good.\n" +
                           "        }\n" +
                           "    }\n" +
                           '\n' +
                           "    @Test\n" +
                           "    public void offsettedReadOf%2$sMustNotBoundsCheckWhenReadOffsetAndSizeIsEqualToWriteOffset() {\n" +
                           "        %1$s value = " + testValue + ";\n" +
                           "        buf.write%2$s(value);\n" +
                           "        assertEquals(value, buf.read%2$s(0));\n" +
                           "    }\n" +
                           '\n' +
                           "    @Test\n" +
                           "    public void offsettedReadOf%2$sMustReadWith" + (le? "Little" : "Big") + "EndianByteOrder() {\n" +
                           "        %1$s value = " + testValue + ";\n" +
                           "        buf.write%2$s(value);\n" +
                           "        buf.writeByte(" + (le? type.actualSize - 1 : 0) + ", (byte) 0x10);\n" +
                           "        assertEquals(" + testValueByteOrder + ", buf.read%2$s(0));\n" +
                           "    }\n" +
                           '\n' +
                           "    @Test\n" +
                           "    public void offsettedReadOf%2$sMustBoundsCheckWhenReadOffsetAndSizeIsGreaterThanWriteOffset() {\n" +
                           "        %1$s value = " + testValue + ";\n" +
                           "        buf.write%2$s(value);\n" +
                           "        try {\n" +
                           "            buf.read%2$s(1);\n" +
                           "            fail(\"Expected a bounds check.\");\n" +
                           "        } catch (IndexOutOfBoundsException ignore) {\n" +
                           "            // Good.\n" +
                           "        }\n" +
                           "    }\n" +
                           '\n' +
                           "    @Test\n" +
                           "    public void offsettedReadOf%2$sMustBoundsCheckWhenReadOffsetIsGreaterThanWriteOffset() {\n" +
                           "        try {\n" +
                           "            buf.read%2$s(0);\n" +
                           "            fail(\"Expected a bounds check.\");\n" +
                           "        } catch (IndexOutOfBoundsException ignore) {\n" +
                           "            // Good.\n" +
                           "        }\n" +
                           "    }";
                return format(tmpl, type, le, unsigned, read);
            }

            @Override
            public String relativeWrite(Type type, boolean le, boolean unsigned, boolean read) {
                prepare(type);
                int size = type.actualSize;
                int r = le? size : 1;
                var tmpl = '\n' +
                           "    @Test\n" +
                           "    public void relativeWriteOf%2$sMustBoundsCheckWhenWriteOffsetAndSizeIsBeyondCapacity() {\n" +
                           "        assertEquals(Long.BYTES, buf.capacity());\n" +
                           "        buf.writerIndex(" + (Long.BYTES + 1 - type.actualSize) + ");\n" +
                           "        try {\n" +
                           "            %1$s value = " + testValue + ";\n" +
                           "            buf.write%2$s(value);\n" +
                           "            fail(\"Should have bounds checked.\");\n" +
                           "        } catch (IndexOutOfBoundsException ignore) {\n" +
                           "            // Good.\n" +
                           "        }\n" +
                           "        buf.writerIndex(Long.BYTES);\n" +
                           "        // Verify contents are unchanged.\n" +
                           "        assertEquals(0, buf.readLong());\n" +
                           "    }\n" +
                           '\n' +
                           "    @Test\n" +
                           "    public void relativeWriteOf%2$sMustHave" + (le? "Little" : "Big") + "EndianByteOrder() {\n" +
                           "        %1$s value = " + testValue + ";\n" +
                           "        buf.write%2$s(value);\n" +
                           "        buf.writerIndex(Long.BYTES);\n" +
                           "        assertEquals((byte) 0x0" + (le? r-- : r++) + ", buf.readByte());\n" +
                           "        assertEquals((byte) 0x0" + (size < 2? 0 : le? r-- : r++) + ", buf.readByte());\n" +
                           "        assertEquals((byte) 0x0" + (size < 3? 0 : le? r-- : r++) + ", buf.readByte());\n" +
                           "        assertEquals((byte) 0x0" + (size < 4? 0 : le? r-- : r++) + ", buf.readByte());\n" +
                           "        assertEquals((byte) 0x0" + (size < 5? 0 : le? r-- : r++) + ", buf.readByte());\n" +
                           "        assertEquals((byte) 0x0" + (size < 6? 0 : le? r-- : r++) + ", buf.readByte());\n" +
                           "        assertEquals((byte) 0x0" + (size < 7? 0 : le? r-- : r++) + ", buf.readByte());\n" +
                           "        assertEquals((byte) 0x0" + (size < 8? 0 : r) + ", buf.readByte());\n" +
                           "    }";
                return format(tmpl, type, le, unsigned, read);
            }

            @Override
            public String offsetWrite(Type type, boolean le, boolean unsigned, boolean read) {
                prepare(type);
                int size = type.actualSize;
                int r = le? size : 1;
                var tmpl = '\n' +
                           "    @Test\n" +
                           "    public void offsettedWriteOf%2$sMustBoundsCheckWhenWriteOffsetIsNegative() {\n" +
                           "        assertEquals(Long.BYTES, buf.capacity());\n" +
                           "        try {\n" +
                           "            %1$s value = " + testValue + ";\n" +
                           "            buf.write%2$s(-1, value);\n" +
                           "            fail(\"Should have bounds checked.\");\n" +
                           "        } catch (IndexOutOfBoundsException ignore) {\n" +
                           "            // Good.\n" +
                           "        }\n" +
                           "        buf.writerIndex(Long.BYTES);\n" +
                           "        // Verify contents are unchanged.\n" +
                           "        assertEquals(0, buf.readLong());\n" +
                           "    }\n" +
                           '\n' +
                           "    @Test\n" +
                           "    public void offsettedWriteOf%2$sMustBoundsCheckWhenWriteOffsetAndSizeIsBeyondCapacity() {\n" +
                           "        assertEquals(Long.BYTES, buf.capacity());\n" +
                           "        try {\n" +
                           "            %1$s value = " + testValue + ";\n" +
                           "            buf.write%2$s(" + (Long.BYTES + 1 - type.actualSize) + ", value);\n" +
                           "            fail(\"Should have bounds checked.\");\n" +
                           "        } catch (IndexOutOfBoundsException ignore) {\n" +
                           "            // Good.\n" +
                           "        }\n" +
                           "        buf.writerIndex(Long.BYTES);\n" +
                           "        // Verify contents are unchanged.\n" +
                           "        assertEquals(0, buf.readLong());\n" +
                           "    }\n" +
                           '\n' +
                           "    @Test\n" +
                           "    public void offsettedWriteOf%2$sMustHave" + (le? "Little" : "Big") + "EndianByteOrder() {\n" +
                           "        %1$s value = " + testValue + ";\n" +
                           "        buf.write%2$s(0, value);\n" +
                           "        buf.writerIndex(Long.BYTES);\n" +
                           "        assertEquals((byte) 0x0" + (le? r-- : r++) + ", buf.readByte());\n" +
                           "        assertEquals((byte) 0x0" + (size < 2? 0 : le? r-- : r++) + ", buf.readByte());\n" +
                           "        assertEquals((byte) 0x0" + (size < 3? 0 : le? r-- : r++) + ", buf.readByte());\n" +
                           "        assertEquals((byte) 0x0" + (size < 4? 0 : le? r-- : r++) + ", buf.readByte());\n" +
                           "        assertEquals((byte) 0x0" + (size < 5? 0 : le? r-- : r++) + ", buf.readByte());\n" +
                           "        assertEquals((byte) 0x0" + (size < 6? 0 : le? r-- : r++) + ", buf.readByte());\n" +
                           "        assertEquals((byte) 0x0" + (size < 7? 0 : le? r-- : r++) + ", buf.readByte());\n" +
                           "        assertEquals((byte) 0x0" + (size < 8? 0 : r) + ", buf.readByte());\n" +
                           "    }";
                return format(tmpl, type, le, unsigned, read);
            }

            private void prepare(Type type) {
                testValue = "0x0102030405060708L";
                if (type.actualSize < 8) {
                    testValue = testValue.substring(0, 2 + type.actualSize * 2);
                }
                testValueByteOrder = testValue.substring(4);
                testValueByteOrder = "0x10" + testValueByteOrder;
                if (type == Type.DOUBLE) {
                    testValue = "Double.longBitsToDouble(" + testValue + ')';
                    testValueByteOrder = "Double.longBitsToDouble(" + testValueByteOrder + ')';
                } else if (type == Type.FLOAT) {
                    testValue = "Float.intBitsToFloat(" + testValue + ')';
                    testValueByteOrder = "Float.intBitsToFloat(" + testValueByteOrder + ')';
                }
                bytesAvailAfter = Long.BYTES - type.actualSize;
            }
        };

        public abstract String relativeRead(Type type, boolean le, boolean unsigned, boolean read);
        public abstract String offsetRead(Type type, boolean le, boolean unsigned, boolean read);
        public abstract String relativeWrite(Type type, boolean le, boolean unsigned, boolean read);
        public abstract String offsetWrite(Type type, boolean le, boolean unsigned, boolean read);
    }

    public static void main(String[] args) throws Exception {
        generateCodeInline(Path.of("buffer/src/main/java/io/netty/buffer/b2/Buf.java"));
        generateCodeInline(Path.of("buffer/src/main/java/io/netty/buffer/b2/BBuf.java"));
        generateCodeInline(Path.of("buffer/src/test/java/io/netty/buffer/b2/BBufTest.java"));
    }

    private static void generateCodeInline(Path path) throws IOException {
        String result;
        try (Stream<String> lines = Files.lines(path)) {
            result = lines.flatMap(processLines()).collect(Collectors.joining("\n"));
        }
        Files.writeString(path, result, UTF_8, TRUNCATE_EXISTING, WRITE);
    }

    private static Function<String, Stream<String>> processLines() {
        return new Function<String, Stream<String>>() {
            final Pattern codegenStart = Pattern.compile("^(\\s*// )### CODEGEN START (.*)$");
            final Pattern codegenEnd = Pattern.compile("^(\\s*// )### CODEGEN END (.*)$");
            boolean inCodeGenRegion;

            @Override
            public Stream<String> apply(String line) {
                if (inCodeGenRegion) {
                    var matcher = codegenEnd.matcher(line);
                    if (matcher.find()) {
                        inCodeGenRegion = false;
                        String regionEnd = matcher.group(1) + "</editor-fold>";
                        return Stream.of(regionEnd, line);
                    }
                    return Stream.empty();
                }

                var matcher = codegenStart.matcher(line);
                Stream<String> generator = Stream.empty();
                if (matcher.find()) {
                    String region = matcher.group(2);
                    var generatorSupplier = REGION_GENERATORS.get(region);
                    if (generatorSupplier != null) {
                        String regionStart =
                                matcher.group(1) + "<editor-fold defaultstate=\"collapsed\" desc=\"Generated " +
                                region + ".\">";
                        generator = Stream.concat(Stream.of(regionStart), generatorSupplier.get());
                        inCodeGenRegion = true;
                    }
                }
                return Stream.concat(Stream.of(line), generator);
            }
        };
    }

    private static Stream<String> primitiveAccessorsInterface() {
        return Arrays.stream(Type.values()).flatMap(type -> generateAccessors(Template.INTERFACE, type));
    }

    private static Stream<String> primitiveAccessorsImplementation() {
        return Arrays.stream(Type.values()).flatMap(type -> generateAccessors(Template.IMPLEMENTATION, type));
    }

    private static Stream<String> primitiveAccessorsTests() {
        return Arrays.stream(Type.values()).flatMap(type -> generateAccessors(Template.TESTS, type));
    }

    private static Stream<String> generateAccessors(Template template, Type type) {
        Builder<String> builder = Stream.builder();

        builder.add(template.relativeRead(type, false, false, true));
        builder.add(template.offsetRead(type, false, false, true));
        if (type.includeLE) {
            builder.add(template.relativeRead(type, true, false, true));
            builder.add(template.offsetRead(type, true, false, true));
        }
        if (type.unsignedCarrier != null) {
            builder.add(template.relativeRead(type, false, true, true));
            builder.add(template.offsetRead(type, false, true, true));
            if (type.includeLE) {
                builder.add(template.relativeRead(type, true, true, true));
                builder.add(template.offsetRead(type, true, true, true));
            }
        }

        builder.add(template.relativeWrite(type, false, false, false));
        builder.add(template.offsetWrite(type, false, false, false));
        if (type.includeLE) {
            builder.add(template.relativeWrite(type, true, false, false));
            builder.add(template.offsetWrite(type, true, false, false));
        }
        if (type.unsignedCarrier != null) {
            builder.add(template.relativeWrite(type, false, true, false));
            builder.add(template.offsetWrite(type, false, true, false));
            if (type.includeLE) {
                builder.add(template.relativeWrite(type, true, true, false));
                builder.add(template.offsetWrite(type, true, true, false));
            }
        }

        return builder.build();
    }

    private static String format(String format, Type type, boolean le, boolean unsigned, boolean read) {
        var carrier = type.type(unsigned);
        var title = type.title(le, unsigned);
        var size = ALL_DIGITS.matcher(type.size).matches()? type.size : "{@link " + type.size.replace('.', '#') + '}';
        var extra = read? type.extraRead(le, unsigned) : type.extraWrite(le, unsigned);
        var realSize = type.size;
        return String.format(format, carrier, title, size, extra, realSize,
                             type.load(le, unsigned), type.store(le, unsigned),
                             type.realType(unsigned));
    }
}
