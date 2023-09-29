/*
 * File: $HeadURL: https://hdt-java.googlecode.com/svn/trunk/hdt-java/src/org/rdfhdt/hdt/util/io/IOUtil.java $
 * Revision: $Rev: 194 $
 * Last modified: $Date: 2013-03-04 21:30:01 +0000 (lun, 04 mar 2013) $
 * Last modified by: $Author: mario.arias $
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation;
 * version 3.0 of the License.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA  02110-1301  USA
 *
 * Contacting the authors:
 *   Mario Arias:               mario.arias@deri.org
 *   Javier D. Fernandez:       jfergar@infor.uva.es
 *   Miguel A. Martinez-Prieto: migumar2@infor.uva.es
 *   Alejandro Andres:          fuzzy.alej@gmail.com
 */
package org.rdfhdt.hdt.util.io;

import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream;
import org.rdfhdt.hdt.compact.integer.VByte;
import org.rdfhdt.hdt.enums.CompressionType;
import org.rdfhdt.hdt.listener.ProgressListener;
import org.rdfhdt.hdt.unsafe.MemoryUtils;
import org.rdfhdt.hdt.unsafe.UnsafeLongArray;
import org.rdfhdt.hdt.util.string.ByteString;
import org.rdfhdt.hdt.util.string.ByteStringUtil;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URL;
import java.net.URLConnection;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.zip.GZIPInputStream;

import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

/**
 * @author mario.arias
 */
public class IOUtil {
	private IOUtil() {
	}

	/**
	 * clean direct allocated buffer
	 * @param buffer the buffer
	 */
	public static void cleanBuffer(ByteBuffer buffer) {
		if (buffer == null) {
			return;
		}

		MemoryUtils.getUnsafe().invokeCleaner(buffer);
	}

	/**
	 * close an object if it implements closeable
	 *
	 * @param object the object (nullable)
	 * @throws IOException close exception
	 */
	public static void closeObject(Object object) throws IOException {
		if (object instanceof Closeable) {
			((Closeable) object).close();
		}
	}

	/**
	 * map a FileChannel, same as {@link FileChannel#map(FileChannel.MapMode, long, long)}, but used to fix unclean map.
	 * @param ch channel to map
	 * @param mode mode of the map
	 * @param position position to map
	 * @param size size to map
	 * @return map buffer
	 * @throws IOException io exception
	 */
	public static CloseMappedByteBuffer mapChannel(String filename, FileChannel ch, FileChannel.MapMode mode, long position, long size) throws IOException {
		return new CloseMappedByteBuffer(filename, ch.map(mode, position, size), false);
	}

	/**
	 * create a large array filled with 0
	 * @param size size
	 * @return array
	 */
	public static UnsafeLongArray createLargeArray(long size) {
		return createLargeArray(size, true);
	}
	/**
	 * create a large array
	 * @param size size
	 * @param init is the array filled with 0 or not
	 * @return array
	 */
	public static UnsafeLongArray createLargeArray(long size, boolean init) {
		return UnsafeLongArray.allocate(size, init);
	}

	/**
	 * call all the close method and merge the exceptions by suppressing them (if multiple)
	 *
	 * @param closeables closeables to close
	 * @throws IOException if one runnable throw an IOException
	 */
	public static void closeAll(Closeable... closeables) throws IOException {
		if (closeables == null || closeables.length == 0) {
			return;
		}
		if (closeables.length == 1) {
			if (closeables[0] != null) {
				closeables[0].close();
			}
			return;
		}
		closeAll(Arrays.asList(closeables));
	}

	/**
	 * call all the close method and merge the exceptions by suppressing them (if multiple)
	 *
	 * @param closeables closeables to close
	 * @throws IOException if one runnable throw an IOException
	 */
	public static void closeAll(Iterable<? extends Closeable> closeables) throws IOException {
		Throwable start = null;
		List<Throwable> throwableList = null;
		for (Closeable runnable : closeables) {
			try {
				if (runnable != null) {
					runnable.close();
				}
			} catch (Throwable e) {
				if (start != null) {
					if (throwableList == null) {
						throwableList = new ArrayList<>();
						throwableList.add(start);
					}
					throwableList.add(e);
				} else {
					start = e;
				}
			}
		}

		// do we have an Exception?
		if (start == null) {
			return;
		}

		if (throwableList == null) {
			throwIOOrRuntime(start);
			return; // remove warnings
		}

		// add the start to the list

		Throwable main = throwableList.stream()
				// get the maximum of severity of the throwable (Error > Runtime > Exception)
				.max(Comparator.comparing(t -> {
					if (t instanceof Error) {
						// worst that can happen
						return 2;
					}
					if (t instanceof RuntimeException) {
						return 1;
					}
					return 0;
				})).orElseThrow();

		throwableList.stream()
				.filter(t -> t != main)
				.forEach(main::addSuppressed);

		throwIOOrRuntime(main);
	}

	/**
	 * throw this throwable as a {@link IOException} or as a {@link RuntimeException}
	 * @param t throwable
	 * @throws IOException t
	 */
	public static void throwIOOrRuntime(Throwable t) throws IOException {
		if (t instanceof IOException) {
			throw (IOException) t;
		}
		if (t instanceof Error) {
			throw (Error) t;
		}
		if (t instanceof RuntimeException) {
			throw (RuntimeException) t;
		}
		throw new RuntimeException(t);
	}

	public static InputStream getFileInputStream(String fileName) throws IOException {
		return getFileInputStream(fileName, true);
	}

	public static InputStream getFileInputStream(String fileName, boolean uncompress) throws IOException {
		InputStream input;
		String name = fileName.toLowerCase();
		if (name.startsWith("http:/") || name.startsWith("https:/") || name.startsWith("ftp:/")) {
			URL url = new URL(fileName);
			URLConnection con = url.openConnection();
			con.connect();
			input = con.getInputStream();
		} else if (name.equals("-")) {
			input = new BufferedInputStream(System.in);
		} else {
			input = new BufferedInputStream(new FileInputStream(fileName));
		}

		if (uncompress) {
			if (name.endsWith(".gz") || name.endsWith(".tgz")) {
				input = new GZIPInputStream(input);
			} else if (name.endsWith("bz2") || name.endsWith("bz")) {
				input = new BZip2CompressorInputStream(input, true);
			} else if (name.endsWith("xz")) {
				input = new XZCompressorInputStream(input, true);
			}
		}
		return input;
	}

	public static BufferedReader getFileReader(String fileName) throws IOException {
		return new BufferedReader(new InputStreamReader(getFileInputStream(fileName)));
	}

	public static String readLine(InputStream in, char character) throws IOException {
		ByteArrayOutputStream buf = new ByteArrayOutputStream();
		while (true) {
			int value = in.read();
			if (value == -1) {
				throw new EOFException();
			}
			if (value == character) {
				break;
			}
			buf.write(value);
		}
		return buf.toString(); // Uses default encoding
	}

	public static String readChars(InputStream in, int numChars) throws IOException {
		StringBuilder out = new StringBuilder();
		for (int i = 0; i < numChars; i++) {
			int c = in.read();
			if (c == -1) {
				throw new EOFException();
			}
			out.append((char) c);
		}
		return out.toString();
	}

	public static void writeString(OutputStream out, String str) throws IOException {
		out.write(str.getBytes(ByteStringUtil.STRING_ENCODING));
	}


	public static void writeSizedBuffer(OutputStream output, byte[] buffer, ProgressListener listener) throws IOException {
		writeSizedBuffer(output, buffer, 0, buffer.length, listener);
	}

	public static void writeSizedBuffer(OutputStream output, ByteString str, ProgressListener listener) throws IOException {
		writeSizedBuffer(output, str.getBuffer(), 0, str.length(), listener);
	}

	public static void writeSizedBuffer(OutputStream output, byte[] buffer, int offset, int length, ProgressListener listener) throws IOException {
		// FIXME: Do by blocks and notify listener
		VByte.encode(output, length);
		output.write(buffer, offset, length);
	}

	public static void writeBuffer(OutputStream output, byte[] buffer, int offset, int length, ProgressListener listener) throws IOException {
		// FIXME: Do by blocks and notify listener
		output.write(buffer, offset, length);
	}

	// Copy the remaining of the Stream in, to out.
	public static void copyStream(InputStream in, OutputStream out) throws IOException {
		byte[] buffer = new byte[1024 * 1024];
		int len;
		while ((len = in.read(buffer)) != -1) {
			out.write(buffer, 0, len);
		}
	}

	// Copy the remaining of the Stream in, to out. Limit to n bytes.
	public static void copyStream(InputStream in, OutputStream out, long n) throws IOException {
		byte[] buffer = new byte[1024 * 1024];
		int len = (int) (buffer.length < n ? buffer.length : n);
		long total = 0;

		while ((total < n) && (len = in.read(buffer, 0, len)) != -1) {
			out.write(buffer, 0, len);

			total += len;
			len = (int) (total + buffer.length > n ? n - total : buffer.length);
		}
	}

	public static void copyFile(File src, File dst) throws IOException {
		FileInputStream in = new FileInputStream(src);
		FileOutputStream out = new FileOutputStream(dst);
		try {
			copyStream(in, out);
		} finally {
			closeQuietly(in);
			closeQuietly(out);
		}
	}

	public static void moveFile(File src, File dst) throws IOException {
		copyFile(src, dst);
		Files.deleteIfExists(src.toPath());
	}

	public static void decompressGzip(File src, File trgt) throws IOException {
		try (InputStream in = new GZIPInputStream(new BufferedInputStream(new FileInputStream(src)))) {
			Files.copy(in, trgt.toPath());
		}
	}

	/**
	 * Write long, little endian
	 *
	 * @param output os
	 * @param value long
	 * @throws IOException io exception
	 */
	public static void writeLong(OutputStream output, long value) throws IOException {
		byte[] writeBuffer = new byte[8];
		writeBuffer[7] = (byte) (value >>> 56);
		writeBuffer[6] = (byte) (value >>> 48);
		writeBuffer[5] = (byte) (value >>> 40);
		writeBuffer[4] = (byte) (value >>> 32);
		writeBuffer[3] = (byte) (value >>> 24);
		writeBuffer[2] = (byte) (value >>> 16);
		writeBuffer[1] = (byte) (value >>> 8);
		writeBuffer[0] = (byte) (value);
		output.write(writeBuffer, 0, 8);
	}


	/**
	 * Read long, little endian.
	 *
	 * @param input is
	 * @throws IOException io exception
	 */
	public static long readLong(InputStream input) throws IOException {
		int n = 0;
		byte[] readBuffer = new byte[8];
		while (n < 8) {
			int count = input.read(readBuffer, n, 8 - n);
			if (count < 0)
				throw new EOFException();
			n += count;
		}

		return ((long) readBuffer[7] << 56) +
				((long) (readBuffer[6] & 255) << 48) +
				((long) (readBuffer[5] & 255) << 40) +
				((long) (readBuffer[4] & 255) << 32) +
				((long) (readBuffer[3] & 255) << 24) +
				((readBuffer[2] & 255) << 16) +
				((readBuffer[1] & 255) << 8) +
				((readBuffer[0] & 255)
				);
	}

	/**
	 * Write int, little endian
	 *
	 * @param output os
	 * @param value value
	 * @throws IOException io exception
	 */
	public static void writeInt(OutputStream output, int value) throws IOException {
		byte[] writeBuffer = new byte[4];
		writeBuffer[0] = (byte) (value & 0xFF);
		writeBuffer[1] = (byte) ((value >> 8) & 0xFF);
		writeBuffer[2] = (byte) ((value >> 16) & 0xFF);
		writeBuffer[3] = (byte) ((value >> 24) & 0xFF);
		output.write(writeBuffer, 0, 4);
	}

	/**
	 * Convert int to byte array, little endian
	 */
	public static byte[] intToByteArray(int value) {
		byte[] writeBuffer = new byte[4];
		writeBuffer[0] = (byte) (value & 0xFF);
		writeBuffer[1] = (byte) ((value >> 8) & 0xFF);
		writeBuffer[2] = (byte) ((value >> 16) & 0xFF);
		writeBuffer[3] = (byte) ((value >> 24) & 0xFF);
		return writeBuffer;
	}

	/**
	 * Read int, little endian
	 *
	 * @param in input
	 * @return integer
	 * @throws IOException io exception
	 */
	public static int readInt(InputStream in) throws IOException {
		int ch1 = in.read();
		int ch2 = in.read();
		int ch3 = in.read();
		int ch4 = in.read();
		if ((ch1 | ch2 | ch3 | ch4) < 0)
			throw new EOFException();
		return (ch4 << 24) + (ch3 << 16) + (ch2 << 8) + (ch1);
	}

	/**
	 * Convert byte array to int, little endian
	 *
	 * @param value io exception
	 */
	public static int byteArrayToInt(byte[] value) {
		return (value[3] << 24) + (value[2] << 16) + (value[1] << 8) + (value[0]);
	}

	public static byte[] readSizedBuffer(InputStream input, ProgressListener listener) throws IOException {
		long size = VByte.decode(input);
		if (size > Integer.MAX_VALUE - 5 || size < 0) {
			throw new IOException("Read bad sized buffer: " + size);
		}
		return readBuffer(input, (int) size, listener);
	}
	/**
	 * @param input    din
	 * @param length   bytes
	 * @param listener listener
	 */
	public static byte[] readBuffer(InputStream input, int length, ProgressListener listener) throws IOException {
		int nRead;
		int pos = 0;
		byte[] data = new byte[length];

		while ((nRead = input.read(data, pos, length - pos)) > 0) {
			// TODO: Notify progress listener
			pos += nRead;
		}

		if (pos != length) {
			throw new EOFException("EOF while reading array from InputStream");
		}

		return data;
	}

	public static CharSequence toBinaryString(long val) {
		StringBuilder str = new StringBuilder(64);
		int bits = 64;
		while (bits-- != 0) {
			str.append(((val >>> bits) & 1) != 0 ? '1' : '0');
		}
		return str;
	}

	public static CharSequence toBinaryString(int val) {
		StringBuilder str = new StringBuilder(32);
		int bits = 32;
		while (bits-- != 0) {
			str.append(((val >>> bits) & 1) != 0 ? '1' : '0');
		}
		return str;
	}

	public static void printBitsln(long val, int bits) {
		printBits(val, bits);
		System.out.println();
	}

	public static void printBits(long val, int bits) {
		while (bits-- != 0) {
			System.out.print(((val >>> bits) & 1) != 0 ? '1' : '0');
		}
	}

	public static short readShort(InputStream in) throws IOException {
		int ch1 = in.read();
		int ch2 = in.read();

		if ((ch1 | ch2) < 0) {
			throw new EOFException();
		}

		return (short) ((ch2 << 8) + (ch1));
	}

	public static void writeShort(OutputStream out, short value) throws IOException {
		out.write(value & 0xFF);
		out.write((value >> 8) & 0xFF);
	}

	public static byte readByte(InputStream in) throws IOException {
		int b = in.read();
		if (b < 0) {
			throw new EOFException();
		}
		return (byte) (b & 0xFF);
	}

	public static void writeByte(OutputStream out, byte value) throws IOException {
		out.write(value);
	}

	// InputStream might not skip the specified number of bytes. This call makes multiple calls
	// if needed to ensure that the desired number of bytes is actually skipped.
	public static void skip(InputStream in, long n) throws IOException {
		if (n == 0) {
			return;
		}

		long totalSkipped = in.skip(n);
		while (totalSkipped < n) {
			totalSkipped += in.skip(n - totalSkipped);
		}
	}

	public static void closeQuietly(Closeable output) {
		if (output == null)
			return;

		try {
			output.close();
		} catch (IOException e) {
			// ignore
		}
	}

	public static InputStream asUncompressed(InputStream inputStream, CompressionType type) throws IOException {
		switch (type) {
			case GZIP:
				return new GZIPInputStream(inputStream);
			case BZIP:
				return new BZip2CompressorInputStream(inputStream, true);
			case XZ:
				return new XZCompressorInputStream(inputStream, true);
			case NONE:
				return inputStream;
		}
		throw new IllegalArgumentException("CompressionType not yet implemented: " + type);
	}

	/**
	 * delete a directory recursively
	 *
	 * @param path directory to delete
	 * @throws IOException io exception
	 */
	public static void deleteDirRecurse(Path path) throws IOException {
		Files.walkFileTree(path, new FileVisitor<>() {
			@Override
			public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
				return FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
				if (!file.getFileName().toString().startsWith(".nfs")) {
					Files.delete(file);
				}
				return FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult visitFileFailed(Path file, IOException exc) {
				return FileVisitResult.TERMINATE;
			}

			@Override
			public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
				Files.delete(dir);
				return FileVisitResult.CONTINUE;
			}
		});
	}

}
