//************************************************************
// Created by: Elliott Shanks, Will Tanna, and Himanshu Mehru
// CSS430, Spring 2014
// FInal Project: File System
// FileTable.java
//*************************************************************

// Used to store the File Entry Table
import java.util.Vector;

public class FileTable
{
	// The actual entity of this file table
	private Vector table;
	// This is the root directory
	private Directory dir;
	// FIleTable constructor
	public FileTable(Directory directory)
	{
		// Instantiate a file (structure) table.
		table = new Vector<FileTableEntry>();
		// Receive a reference to the Director from the file system.
		dir = directory;
	}
	// The following method allcates a new file and
	// returns a reference to this file table entry
	public synchronized FileTableEntry falloc( String filename, String mode )
	{
		// Set to -1 as default until file is found.
		short fileNumber = -1;
		Inode iNode = null;
		// Get the mode for the specific file.
		short fileMode = FileTableEntry.convertModeToShort(mode);

		while (true)
		{
			// Retrieve the corresponding Inode.
			fileNumber = filename.equals("/") ? 0 : dir.namei(filename);
			// If the number is still -1, the file hasn't been found.
			if (fileNumber == -1)
			{
				// If it's read only or if ialloc() returns -1, don't allocate!
				if (fileMode == 3 ||(fileNumber = dir.ialloc(filename)) == -1)
				{
					return null;
				}
				// Allocate new Inode and break out of the loop.
				iNode = new Inode();
				break;
			}
			// Allocate the Inode with the fileNumber.
			iNode = new Inode(fileNumber);

			// Check the Inode's flag and determine whether to wait.
			// If the flags are set to 0 or 1, then we don't
			// have to wait. Similarly, if the flag is set to READ on a
			// READONLY mode, we can stop waiting as well.
			if (iNode.flag == 0 || iNode.flag == 1 || (fileMode == 3 &&
					iNode.flag == 2))
			{
				// We don't have to wait, so break out of the loop.
				break;
			}
			// If the flag is set to 4, there aren't going to be
			// any more opens, so set fileNumber back to -1 and return.
			if (iNode.flag == 4)
			{
				fileNumber = -1;
				return null;
			}

			// All the other flags for the file modes must wait.
			try { wait(); } catch (InterruptedException e) {}
		}

		// Increment the iNode's count because another entry has been added.
		iNode.count++;
		// Write Inode to the disk.
		iNode.toDisk(fileNumber);
		// Create a new file table entry.
		FileTableEntry e = new FileTableEntry(iNode, fileNumber, mode);
		// Add the file entry to table
		table.addElement(e);
		// Return the entry that's just added.
		return e;
	}
	// The following method frees a file table entry.
	public synchronized boolean ffree( FileTableEntry e )
	{
		// If the specific file table entry is null.
		// then it's already free.
		if (e == null)
		{
			return true;
		}
		// If the specific file table entry is not found then return false.
		if (table.removeElement(e) == false)
		{
			return false;
		}
		// If there are no entries in the file table, set the flag to 0.
		if (e.inode.count == 0)
		{
			e.inode.flag = 0;
		}
		// Else decrement the Inode's count to keep track
		// of the number of file table entires.
		if (e.inode.count > 0)
		{
			e.inode.count--;
		}
		// Save the corresponding iNode to the disk
		e.inode.toDisk(e.iNumber);
		// Notify all the waiting threads.
		if (e.inode.flag == 2 || e.inode.flag == 3)
		{
			notify();
		}
		// Free the file table entry by setting it to null.
		e = null;
		// The file table entry was successfuly found and freed.
		return true;
	}
	// The following method checks to see if the table is empty.
	public synchronized boolean fempty( )
	{
		// Return if table is empty.
		return table.isEmpty( );
	}
}// End of FileTable.java
