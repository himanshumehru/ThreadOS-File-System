//************************************************************
// Created by: Elliott Shanks, Will Tanna, and Himanshu Mehru
// CSS430, Spring 2014
// FInal Project: File System
// SuperBlock.java
//*************************************************************

// OS managed structure used to describe (1) the number of disk
// blocks, (2) the number of inodes, and (3) the block number of
// the head blocks of the free list.
public class SuperBlock
{
	// If the disk needs to be formatted, then the following
	// default number of files will be created.
	private final static int defaultInodeBlocks = 64;
	// The number of disk blocks.
	public int totalBlocks;
	// The number of inodes.
	public int totalInodes;
	// the block number of the free list's head
	public int freeList;

	// The following is the constructor for SuperBlock. It reads the
	// Superblock from the disk and determines the total number
	// of diskblocks, Inodes, and where the free list should start.
	public SuperBlock(int diskSize)
	{
		// Byte array that will store superblock.
		byte[] superBlock = new byte[Disk.blockSize];
		// Reads in the superblock from the disk.
		SysLib.rawread(0, superBlock);
		// Compute the total amount of disk blocks.
		totalBlocks = SysLib.bytes2int(superBlock, 0);
		// Compute the total number of Inodes.
		totalInodes = SysLib.bytes2int(superBlock, 4);
		// Determine where the free list should start.
		freeList = SysLib.bytes2int(superBlock, 8);

		// If the disk content is valid...
		if (totalBlocks == diskSize &&
			totalInodes > 0 && freeList >= 2)
		{
			return;
		}
		// Else we need to format the disk with the
		// default number of inodes to be allocated.
		else
		{
			totalBlocks = diskSize;
			// Format the disk.
			format( defaultInodeBlocks );
		}
	}

	// The following method determines how many blocks should
	// be allocated for Inodes and where the free list should start.
	public void format( int files )
	{
		// Used to determine where the free list should start.
		int offset;
		// Used to hold the data that will be written to the disk.
		byte[] data = null;
		// Set the total number of Inodes to be allocated.
		totalInodes = files;

		// The following for loop allocates every Inode to the disk.
		for (int i = 0; i < totalInodes; i++)
		{
			Inode allocate = new Inode();
			allocate.toDisk((short) i);
		}
		// If there are exactly 16 Inodes in every block (every block
		// is full), then the free list starts at the very next block
		// (Offset = 1).However, if there's a remainder, then the next
		// block must hold that remainder, so the offset = 2.
		if ( files % 16 == 0 )
		{
			offset = 1;
		}
		else
		{
			offset  = 2;
		}
		// Determine the the block number of the free list's head.
		freeList = ( files / 16 + offset );

		// For loop that iterates through each block in the free list.
		for (int i = freeList; i < totalBlocks; i++)
		{
			// Create a new block that will hold the data.
			data = new byte[Disk.blockSize];
			// Initialize the data with zeros.
			for (int j = 0; j < Disk.blockSize; j++)
			{
				data[j] = (byte) 0;
			}
			// Write the block to the disk.
			SysLib.int2bytes(i + 1, data, 0);
			SysLib.rawwrite(i, data);
		}

		sync();
	}
	// The following function writes back totalBlocks,
	// totalInodes, and freeList to the disk.
	public void sync()
	{
		// Create byte array to hold block data.
		byte[] data = new byte[Disk.blockSize];

		// Write the total disk blocks, Inodes,
		// and the free list head to the disk.
		SysLib.int2bytes(totalBlocks, data, 0);
		SysLib.int2bytes(totalInodes, data, 4);
		SysLib.int2bytes(freeList, data, 8);
		SysLib.rawwrite(0, data);
	}
	// The following method dequeues the top block from the free list.
	public short getFreeBlock()
	{
		// Check if there are no more free blocks
		if(freeList < 0 || freeList > totalBlocks){
			return -1;
		}
		// Create byte array to hold block data.
		byte[] data = new byte[Disk.blockSize];
		// Mark the block we are to dequeue.
		short freeBlock = (short)freeList;
		// Read the block's data from the disk.
		SysLib.rawread(freeList, data);
		SysLib.int2bytes(0, data, 0);
		// Get the next free block.
		freeList = SysLib.bytes2int(data, 0);
		// Return the dequeued block.
		return freeBlock;
	}
	// The following method enqueues a given
	// block to the the free list.
	public boolean returnBlock(int blockNumber)
	{
		// Check parameter first
		if(blockNumber < 0 || blockNumber > totalBlocks){
			return false;
		}
		// Create byte array to hold block data.
		byte[] data = new byte[Disk.blockSize];

		// Add the block to the end of the free list
		// and write it to the disk.
		SysLib.int2bytes(freeList, data, 0);
		SysLib.rawwrite(blockNumber, data);
		// Set the free list.
		freeList = blockNumber;
		// The block has been enqueued successfully.
		return true;
	}
}// End of SuperBlock.java
