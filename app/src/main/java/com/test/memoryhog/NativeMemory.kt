package com.test.memoryhog

class NativeMemory {

    /**
     * Allocates [mb] megabytes of anonymous shared memory via mmap().
     * Physical RAM pages are committed immediately via memset().
     *
     * @return number of bytes actually allocated, or -1 on failure
     */
    external fun allocateMB(mb: Int): Long

    /**
     * Returns total bytes currently allocated across all mmap regions.
     */
    external fun getTotalAllocatedBytes(): Long

    /**
     * Releases all mmap allocations, returning RAM to the OS immediately.
     */
    external fun releaseAll()

    /**
     * Releases approximately [mb] MB worth of the most recent allocations.
     */
    external fun releaseLastMB(mb: Int)

    companion object {
        init {
            System.loadLibrary("memoryhog")
        }
    }
}
