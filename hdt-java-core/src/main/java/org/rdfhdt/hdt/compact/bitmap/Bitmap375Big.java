/**
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation;
 * version 3.0 of the License.
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA  02110-1301  USA
 * Contacting the authors:
 * Dennis Diefenbach:         dennis.diefenbach@univ-st-etienne.fr
 */

package org.rdfhdt.hdt.compact.bitmap;

import org.rdfhdt.hdt.hdt.HDTVocabulary;
import org.rdfhdt.hdt.listener.ProgressListener;
import org.rdfhdt.hdt.util.BitUtil;
import org.rdfhdt.hdt.util.disk.LargeLongArray;
import org.rdfhdt.hdt.util.disk.LongArray;
import org.rdfhdt.hdt.util.disk.LongArrayDisk;
import org.rdfhdt.hdt.util.disk.SimpleSplitLongArray;
import org.rdfhdt.hdt.util.io.CloseSuppressPath;
import org.rdfhdt.hdt.util.io.Closer;
import org.rdfhdt.hdt.util.io.IOUtil;
import org.visnow.jlargearrays.LongLargeArray;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;

/**
 * Implements an index on top of the Bitmap64 to solve select and rank queries more efficiently. Can store using disk
 * and memory implementations without any limitation in size.
 * <p>
 * index -&gt; O(n)
 * rank1 -&gt; O(1)
 * select1 -&gt; O(log log n)
 *
 * @author mario.arias
 */
public class Bitmap375Big extends Bitmap64Big {
    /**
     * create disk version bitmap with in memory super index
     *
     * @param location location
     * @param nbits    number of bits
     * @return bitmap
     */

    public static Bitmap375Big disk(Path location, long nbits) {
        return disk(location, nbits, false);
    }

    /**
     * create disk version bitmap
     *
     * @param location          location
     * @param nbits             number of bits
     * @param useDiskSuperIndex use disk super index
     * @return bitmap
     */
    public static Bitmap375Big disk(Path location, long nbits, boolean useDiskSuperIndex) {
        return new Bitmap375Big(new LongArrayDisk(location, numWords(nbits)), location, useDiskSuperIndex);
    }

    /**
     * create memory version bitmap
     *
     * @param nbits number of bits
     * @return bitmap
     */
    public static Bitmap375Big memory(long nbits) {
        return memory(nbits, null);
    }

    /**
     * create memory version bitmap with on disk super index
     *
     * @param nbits    number of bits
     * @param location location for the disk super index (if on disk)
     * @return bitmap
     */
    public static Bitmap375Big memory(long nbits, Path location) {
        return new Bitmap375Big(new LargeLongArray(new LongLargeArray(numWords(nbits))), location, location != null);
    }

    // Constants
    private static final int BLOCKS_PER_SUPER = 4;

    // Variables
    private long pop;
    private LongArray superBlocks;
    private LongArray blocks;
    private boolean indexUpToDate;
    private final boolean useDiskSuperIndex;
    private final CloseSuppressPath superBlocksPath;
    private final CloseSuppressPath blocksPath;


    protected Bitmap375Big(LongArray words, Path location, boolean useDiskSuperIndex) {
        super(words);
        this.useDiskSuperIndex = useDiskSuperIndex;
        if (useDiskSuperIndex) {
            CloseSuppressPath path = CloseSuppressPath.of(location);
            this.superBlocksPath = path.resolveSibling(path.getFileName() + ".sb");
            this.blocksPath = path.resolveSibling(path.getFileName() + ".bp");
        } else {
            this.superBlocksPath = null;
            this.blocksPath = null;
        }
        getCloser().with((Closeable) this::closeObject);
    }

    private void closeObject() throws IOException {
        Closer.closeAll(superBlocks, superBlocksPath, blocks, blocksPath);
    }


    /**
     * dump the bitmap in stdout
     */
    public void dump() {
        long count = numWords(this.numbits);
        for (long i = 0; i < count; i++) {
            System.out.print(i + "\t");
            IOUtil.printBitsln(words.get(i), 64);
        }
    }

    /**
     * update the index
     */
    public void updateIndex() {
        trimToSize();
        try {
            Closer.closeAll(superBlocks, blocks, superBlocksPath, blocksPath);
        } catch (IOException e) {
            // ignore
        }
        if (useDiskSuperIndex) {
            if (numbits > Integer.MAX_VALUE) {
                superBlocks = SimpleSplitLongArray.int64ArrayDisk(superBlocksPath, 1 + (words.length() - 1) / BLOCKS_PER_SUPER);
            } else {
                superBlocks = SimpleSplitLongArray.int32ArrayDisk(superBlocksPath, 1 + (words.length() - 1) / BLOCKS_PER_SUPER);
            }
            blocks = SimpleSplitLongArray.int8ArrayDisk(blocksPath, words.length());
        } else {
            if (numbits > Integer.MAX_VALUE) {
                superBlocks = SimpleSplitLongArray.int64Array(1 + (words.length() - 1) / BLOCKS_PER_SUPER);
            } else {
                superBlocks = SimpleSplitLongArray.int32Array(1 + (words.length() - 1) / BLOCKS_PER_SUPER);
            }
            blocks = SimpleSplitLongArray.int8Array(words.length());
        }

        long countBlock = 0, countSuperBlock = 0;
        long blockIndex = 0, superBlockIndex = 0;

        while (blockIndex < words.length()) {
            if ((blockIndex % BLOCKS_PER_SUPER) == 0) {
                countSuperBlock += countBlock;
                superBlocks.set(superBlockIndex++, countSuperBlock);
                countBlock = 0;
            }
            blocks.set(blockIndex, countBlock);
            countBlock += Long.bitCount(words.get(blockIndex));
            blockIndex++;
        }
        pop = countSuperBlock + countBlock;
        indexUpToDate = true;
    }

    /* (non-Javadoc)
     * @see hdt.compact.bitmap.Bitmap#access(long)
     */
    @Override
    public boolean access(long bitIndex) {
        if (bitIndex < 0)
            throw new IndexOutOfBoundsException("bitIndex < 0: " + bitIndex);

        long wordIndex = wordIndex(bitIndex);
        if (wordIndex >= words.length()) {
            return false;
        }

        return (words.get(wordIndex) & (1L << bitIndex)) != 0;
    }

    @Override
    public void set(long bitIndex, boolean value) {
        indexUpToDate = false;
        super.set(bitIndex, value);
    }

    /* (non-Javadoc)
     * @see hdt.compact.bitmap.Bitmap#rank1(long)
     */
    @Override
    public long rank1(long pos) {
        if (pos < 0) {
            return 0;
        }
        if (!indexUpToDate) {
            updateIndex();
        }
        if (pos >= numbits) {
            return pop;
        }

        long superBlockIndex = pos / (BLOCKS_PER_SUPER * W);
        long superBlockRank = superBlocks.get(superBlockIndex);

        long blockIndex = pos / W;
        long blockRank = 0xFF & blocks.get(blockIndex);

        long chunkIndex = W - 1 - pos % W;
        long block = words.get(blockIndex) << chunkIndex;
        long chunkRank = Long.bitCount(block);

        return superBlockRank + blockRank + chunkRank;
    }

    /* (non-Javadoc)
     * @see hdt.compact.bitmap.Bitmap#rank0(long)
     */
    @Override
    public long rank0(long pos) {
        return pos + 1L - rank1(pos);
    }

    /* (non-Javadoc)
     * @see hdt.compact.bitmap.Bitmap#select0(long)
     */
    @Override
    public long select0(long x) {
        //System.out.println("\t**Select0 for: "+ x);

        if (x < 0) {
            return -1;
        }
        if (!indexUpToDate) {
            updateIndex();
        }
        if (x > numbits - pop) {
            return numbits;
        }

        // Search superblock (binary Search)
        long superBlockIndex = binarySearch0(superBlocks, 0, superBlocks.length(), x);
        if (superBlockIndex < 0) {
            // Not found exactly, gives the position where it should be inserted
            superBlockIndex = -superBlockIndex - 2;
        } else if (superBlockIndex > 0) {
            // If found exact, we need to check previous block.
            superBlockIndex--;
        }

        // If there is a run of many ones, two correlative superblocks may have the same value,
        // We need to position at the first of them.
        while (superBlockIndex > 0 && (superBlockIndex * BLOCKS_PER_SUPER * W - superBlocks.get(superBlockIndex) >= x)) {
            superBlockIndex--;
        }

        long countdown = x - (superBlockIndex * BLOCKS_PER_SUPER * W - superBlocks.get(superBlockIndex));
        long blockIdx = superBlockIndex * BLOCKS_PER_SUPER;

        // Search block
        while (true) {
            if (blockIdx >= (superBlockIndex + 1) * BLOCKS_PER_SUPER || blockIdx >= blocks.length()) {
                blockIdx--;
                break;
            }
            if ((0xFF & (W * (blockIdx % BLOCKS_PER_SUPER) - (0xFF & blocks.get(blockIdx)))) >= countdown) {
                // We found it!
                blockIdx--;
                break;
            }
            blockIdx++;
        }
        if (blockIdx < 0) {
            blockIdx = 0;
        }
        countdown = countdown - (0xFF & ((blockIdx % BLOCKS_PER_SUPER) * W - blocks.get(blockIdx)));

        // Search bit inside block
        long bitpos = BitUtil.select0(words.get(blockIdx), (int) countdown);

        return (blockIdx) * W + bitpos - 1;
    }

    /* (non-Javadoc)
     * @see hdt.compact.bitmap.Bitmap#select1(long)
     */
    @Override
    public long select1(long x) {
        //System.out.println("\t**Select1 for: "+ x);

        if (x < 0) {
            return -1;
        }
        if (!indexUpToDate) {
            updateIndex();
        }
        if (x > pop) {
            return numbits;
        }
        if (numbits == 0) {
            return 0;
        }
        // Search superblock (binary Search)
        long superBlockIndex = binarySearch(superBlocks, x);

        // If there is a run of many zeros, two correlative superblocks may have the same value,
        // We need to position at the first of them.

        while (superBlockIndex > 0 && (superBlocks.get(superBlockIndex) >= x)) {
            superBlockIndex--;

        }

        long countdown = x - superBlocks.get(superBlockIndex);

        long blockIdx = superBlockIndex * BLOCKS_PER_SUPER;

        // Search block
        while (true) {
            if (blockIdx >= (superBlockIndex + 1) * BLOCKS_PER_SUPER || blockIdx >= blocks.length()) {
                blockIdx--;
                break;
            }
            if ((0xFF & blocks.get(blockIdx)) >= countdown) {
                // We found it!
                blockIdx--;
                break;
            }
            blockIdx++;
        }
        if (blockIdx < 0) {
            blockIdx = 0;
        }
        countdown = countdown - (0xFF & blocks.get(blockIdx));

        // Search bit inside block
        int bitpos = BitUtil.select1(words.get(blockIdx), (int) countdown);

        return blockIdx * W + bitpos - 1;
    }

    /* (non-Javadoc)
     * @see hdt.compact.bitmap.Bitmap#countOnes()
     */
    @Override
    public long countOnes() {
        return rank1(numbits);
    }

    /* (non-Javadoc)
     * @see hdt.compact.bitmap.Bitmap#countZeros()
     */
    @Override
    public long countZeros() {
        return numbits - countOnes();
    }

    @Override
    public long getRealSizeBytes() {
        updateIndex();

        return super.getRealSizeBytes()
                + blocks.length() * blocks.sizeOf() / 8
                + superBlocks.length() * superBlocks.sizeOf() / 8;
    }

    /* (non-Javadoc)
     * @see hdt.compact.bitmap.Bitmap#getType()
     */
    @Override
    public String getType() {
        return HDTVocabulary.BITMAP_TYPE_PLAIN;
    }

    /* (non-Javadoc)
     * @see hdt.compact.bitmap.Bitmap#load(java.io.InputStream, hdt.listener.ProgressListener)
     */
    @Override
    public void load(InputStream input, ProgressListener listener) throws IOException {
        super.load(input, listener);
        updateIndex();
    }


    /**
     * run a binary search in an array with the 0 values
     *
     * @param arr       array
     * @param fromIndex start index (inclusive)
     * @param toIndex   max index (exclusive)
     * @param key       key to search
     * @return index
     */
    public static long binarySearch0(LongArray arr, long fromIndex, long toIndex, long key) {
        long low = fromIndex;
        long high = toIndex - 1;

        while (low <= high) {
            long mid = (low + high) >>> 1;
            long midVal = mid * BLOCKS_PER_SUPER * W - arr.get(mid);

            if (midVal < key) {
                low = mid + 1;
            } else if (midVal > key) {
                high = mid - 1;
            } else {
                return mid; // key found
            }
        }
        return -(low + 1);  // key not found.
    }

    /**
     * binary search val index into arr
     *
     * @param arr arr
     * @param val val
     * @return index
     */
    public static long binarySearch(LongArray arr, long val) {
        long min = 0, max = arr.length(), mid;

        while (min + 1 < max) {
            mid = (min + max) / 2;

            if (arr.get(mid) >= val) {
                max = mid;
            } else {
                min = mid;
            }
        }

        return min;
    }

    public CloseSuppressPath getBlocksPath() {
        return blocksPath;
    }

    public CloseSuppressPath getSuperBlocksPath() {
        return superBlocksPath;
    }
}