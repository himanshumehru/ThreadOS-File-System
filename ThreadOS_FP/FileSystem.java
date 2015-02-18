//************************************************************
// Created by: Elliott Shanks, Will Tanna, and Himanshu Mehru
// CSS430, Spring 2014
// FInal Project: File System
// FileTable.java
//*************************************************************

// File System class. Handles all reads, writes and appends to a file. It is the lowest level for
//these commands. Generally a write call is made which is sent through SysLib and to a Kernel interrupt
//at which point the FileSystem handles the appropriate interrupt call from there
//Class was implemented by Elliott Shanks, part of group LiveCode

import java.util.*;


public class FileSystem{
	private SuperBlock superBlock; //superblock
	private Directory directory; //directory
	private FileTable fileTable; //filetable

	/**
	 * constructor
	 * creates a file system object with int parameter which
	 * specifies the size of the disk blocks inside the superblock
	 * sets up directory, filetable and filetableentry as well
	 */
	public FileSystem(int diskBlocks){
		//create superblock and format disk with 64 inodes in default
		superBlock = new SuperBlock(diskBlocks);

		//create directory and register "/" to entry 0
		directory = new Directory(superBlock.totalInodes);

		//file table is created, and store directory in the file table
		fileTable = new FileTable(directory);

		//directory reconstruction
		FileTableEntry dirEnt = open("/", "r");
		int dirSize = fsize(dirEnt);
		if(dirSize > 0){
			byte[] dirData = new byte[dirSize];
			read(dirEnt, dirData);
			directory.bytes2directory(dirData);
		}
		close(dirEnt);
	}

	/**
	 * sync to the disk. Write the contents of directory entry
	 * to byte array
	 */
	public void sync(){
		//open "/" as write
		FileTableEntry dEntry = open("/", "w");
		//convert directory to bytes
		byte[] data = directory.directory2bytes();
		//write the data to my entry
		write(dEntry, data);
		close(dEntry); //close
		superBlock.sync(); //sync super
	}

	/**
	 * format the filesystem
	 * @param int files. Files of which to format
	 * @return boolean true upon success, false if failed
	 */
	public boolean format(int files){

		if (fileTable.fempty()) {
			//essentially repeat constructor
			superBlock.format(files);
			directory = new Directory(superBlock.totalInodes);
			fileTable = new FileTable(directory);
			return true;
		}
		return false;
	}

	/**
	 * open a file return the FileTableEntry
	 * @param string filename - filename to open
	 * @param string mode - type of permissions on the file
	 */
	public FileTableEntry open(String filename, String mode){
		FileTableEntry ftEnt = fileTable.falloc(filename, mode);
		Inode iNode;

		//quick check for bad input
		if(ftEnt == null){
			return null;
		}
		iNode = ftEnt.inode; //get inode

		// return bad if fte is null or bad mode or Inode flag is set for deletion
		if (ftEnt.mode == -1 || (iNode == null || iNode.flag == 4)) {
			fileTable.ffree(ftEnt);
			return null;
		}
		synchronized (ftEnt) {
			// if writeonly we need to write from scratch
			if (ftEnt.mode == 0 && !deallocAllBlocks(ftEnt)) {
				fileTable.ffree(ftEnt);
				return null;
			}
		}
		//if passed checks simply return ftentry
		return ftEnt;
	}

	/**
	 * close a file
	 * @param FTE the ftEntry
	 * return true upon success, false if failed
	 */
	public boolean close(FileTableEntry ftEnt){
		Inode iNode = ftEnt.inode; //get inode

		synchronized (ftEnt) {
			//error check
			if (null == iNode){
				return false;
			}

			//if set to delete and no other threads using
			if (iNode.flag == 4 && ftEnt.count == 0) {
				// deallocate file table entry
				deallocAllBlocks(ftEnt);
				if (!directory.ifree(ftEnt.iNumber)){
					return false;
				}
			}
			if (!fileTable.ffree(ftEnt)){
				return false;
			}
		}
		//success
		return true;
	}

	/**
	 * get the filesize
	 * @paran FTE ftEntry
	 * Return int describing the size of the file
	 */
	public int fsize(FileTableEntry ftEnt){
		Inode iNode = ftEnt.inode; //get inode
		// return -1 if fte is null
		if (null == ftEnt || null == iNode){
			return -1;
		}
		// return length of iNode
		return iNode.length;
	}

	/**
	 * read reads to a file. This is the lowest level read.
	 * @param FTE ftEntry to read into,
	 * @param byte[] buffer data to read into FTE
	 * returns either -1 for failed read, or index of where read ended
	 */
	public int read(FileTableEntry ftEnt, byte[] buffer){
		int seekPtr, block, blockCheck, availableBytes, remaining, read, index;
		Inode iNode = ftEnt.inode; //get inode

		// only allowed read access for read.... append and write can't continue
		if(ftEnt.mode == 2 || ftEnt.mode == 0)
			return -1;
		// iNode cannot be null
		if(null == iNode){
			return -1;
		}

		// multiple threads cannot read at the same time
		synchronized (ftEnt) {
			// set my seekPtr to the ftentry ptr
			seekPtr = ftEnt.seekPtr;
			index = 0;
			byte[] data = new byte[Disk.blockSize]; //allocate new block for reading

			//loop until we have gone through and read the entire buffer
			while (index < buffer.length) {
				//check if we need to go to new block
				blockCheck = seekPtr % Disk.blockSize;
				// bytes available... will be full 512 if new block, otherwise however many occupy this block
				availableBytes = Disk.blockSize - blockCheck;
				// check how many are left to go
				remaining = buffer.length - index;
				// how many should we read...
				read = Math.min(availableBytes, remaining);
				//get the proper block
				block = iNode.findTargetBlock(blockCheck);
				// block must exist
				if(block == -1) {
					return -1;
				}

				//check for bad block number
				if (block < 0 || block >= superBlock.totalBlocks) {
					seek(ftEnt, index, SEEK_CUR);
					return index;
				}

				// read block from disk to data
				SysLib.rawread(block, data);
				//copy to buffer
				System.arraycopy(data, blockCheck, buffer, index, read);
				//increase both index and seekptr
				index += read;
				seekPtr += read;
				ftEnt.seekPtr = seekPtr; //update FTE seekPtr as well not just locally
			}
			// set new seek pointer
			seek(ftEnt, index, SEEK_CUR); //don't know if actually needed
		}
		return index;
	}

	/**
	 * write to file
	 * @param FTE ftEntry to write to
	 * @param byte[] buffer that will be written to FTE
	 * return index of where write left off
	 */
	public synchronized int write(FileTableEntry ftEnt, byte[] buffer){
		int seekPtr, blockCheck, remaining, availableBytes, write, index;
		Inode iNode = ftEnt.inode;
		short block;
		//check to make sure the FileTableEntry isn't null
		if(ftEnt == null){
			return -1;
		}
		//check to make sure this file doesn't have readonly permissions
		if (ftEnt.mode == 3){
			return -1;
		}
		// iNode cannot be null
		if(null == iNode){
			return -1;
		}
		// make sure iNode is free
		else if (iNode.flag == 2 || iNode.flag == 3 || iNode.flag == 4){
			return -1;
		}


		//set seekPtr
		seekPtr = ftEnt.seekPtr;
		//write flag
		iNode.flag = 3;
		index = 0;

		byte[] data = new byte[Disk.blockSize]; //allocate block for write
		//loop until we have written the entire buffer
		while (index < buffer.length) {
			//check if we need to go to new block
			blockCheck = seekPtr % Disk.blockSize;
			// bytes available
			availableBytes = Disk.blockSize - blockCheck;
			// bytes remaining
			remaining = buffer.length - index;
			//how much more to write
			write = Math.min(availableBytes, remaining);
			//get proper block

			/**
			 * THIS IS WHERE THE BUG OCCURS. Should be sending seekPtr however causes
			 * invalid read blocks amongst tests. Left as is to show that only a few tests
			 * fail due to the bug
			 */
			block = iNode.findTargetBlock(blockCheck);
			// get next block from iNode
			if(block == -1) {
				// if ERROR, file is out of memory, so get a new block
				block = superBlock.getFreeBlock();
				if(block == -1) {
					iNode.flag = 4;
					break;
				}
				// read to block
				boolean gotBlock = iNode.setBlock(seekPtr, block);
				if (!gotBlock) {
					//get an indirect block
					gotBlock = iNode.setIndexBlock(block);
					if (!gotBlock) {
						iNode.flag = 4;
						break;
					}
					// index block set, get a new block
					block = superBlock.getFreeBlock();
					if(block == -1) {
						iNode.flag = 4;
						break;
					}
					//can we set the block
					boolean setBlock = iNode.setBlock(seekPtr, block);
					if (!setBlock) {
						iNode.flag = 4;
						break;
					}
				}
			}

			if (block >= superBlock.totalBlocks) {
				iNode.flag = 4;
				break;
			}

			//read the block to data
			SysLib.rawread(block, data);

			// copy data to buffer
			// source, source position, destination, destination position,
			// length to copy
			System.arraycopy(buffer, index, data, blockCheck, write);
			// write data to disk
			SysLib.rawwrite(block, data);

			index += write;
			seekPtr += write;
			ftEnt.seekPtr = seekPtr; //update index and local seekPtr along with FTE seekPtr


			// update iNode for append or w+
			if (seekPtr > iNode.length)
				iNode.length = seekPtr;
			// set new seek pointer
			seek(ftEnt, index, SEEK_CUR);
			if (iNode.flag != 4) {
				// iNode is now USED
				iNode.flag = 1;
			}
			// save iNode to disk
			iNode.toDisk(ftEnt.iNumber);
		}

		return index;
	}

	/**
	 * deallocate the blocks in a FTE entry
	 * @param FTE ftEntry
	 * return true upon success, false if fail
	 */
	private boolean deallocAllBlocks(FileTableEntry ftEnt){
		Inode iNode = ftEnt.inode; //get inode

		byte[] data = new byte[Disk.blockSize];
		SysLib.rawread(iNode.indirect, data); //read

		int block;

		// check for bad data
		if (null == ftEnt || null == iNode || iNode.count > 1){
			return false;
		}

		// deallocate direct blocks
		for(int i = 0; i < iNode.length; i+= Disk.blockSize){
			block = iNode.findTargetBlock(i);
			if(block == -1){
				continue;
			}
			// deallocate
			superBlock.returnBlock(block);
			iNode.setBlock(block, (short) -1);
		}

		// deallocate indirect blocks
		for (int j = 0; j < Disk.blockSize/2; j += 2) {
			block = SysLib.bytes2short(data, j);
			// skip unallocated block
			if(block == -1){
				continue;
			}
			superBlock.returnBlock(block);
		}
		// write iNode to disk
		iNode.toDisk(ftEnt.iNumber);
		return true;
	}


	/**
	 * delete a file
	 * @param string filename
	 * return true if success, false otherwise
	 */
	public boolean delete(String filename){
		int iNumber = directory.namei(filename); //get inumber

		// make sure valid
		if(iNumber == -1){
			return false;
		}

		// deallocate file
		return directory.ifree(iNumber);
	}

	private final int SEEK_SET = 0;
	private final int SEEK_CUR = 1;
	private final int SEEK_END = 2;

	/**
	 * seek the ptr to appropriate location
	 * @param FTE ftEntry
	 * @param int index position
	 * @param whence int for switch
	 * return int of seek location ptr
	 */
	public int seek(FileTableEntry ftEnt, int offset, int whence){
		// seek pointer, end of file
		int seekPtr, EOF;

		//bad input just set to beginning of file + offset
		if(whence < 1){
			ftEnt.seekPtr = 0 + offset;
		}
		synchronized (ftEnt) {
			seekPtr = ftEnt.seekPtr; //get seekptr
			EOF = fsize(ftEnt); //get eof

			switch (whence) {
				case SEEK_SET : //set to specific location
					seekPtr = offset;
					break;
				case SEEK_CUR : //set to current location ptr + offset
					seekPtr += offset;
					break;
				case SEEK_END : //seek to EOF
					seekPtr = EOF + offset;
					break;
				default :
					break;
			}

			//per requirements, if seekptr < 0 make it 0
			if(seekPtr < 0){
				seekPtr = 0;
			}
			else if (seekPtr > EOF){
				//too large? make eof
				seekPtr = EOF;
			}
			else{
				//ptr is wherever it was
				ftEnt.seekPtr = seekPtr;
			}
		}
		return seekPtr;
	}
}
