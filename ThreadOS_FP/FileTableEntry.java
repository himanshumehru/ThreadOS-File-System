//************************************************************
// Created by: Elliott Shanks, Will Tanna, and Himanshu Mehru
// CSS430, Spring 2014
// FInal Project: File System
// FileTableEntry.java
//*************************************************************

public class FileTableEntry {  // Each table entry should have
    public int seekPtr;        //    a file seek pointer
    public final Inode inode;  //    a reference to an inode
    public final short iNumber;//    this inode number
    public int count;          //    a count to maintain #threads sharing this
    public final int mode;  //    "r", "w", "w+", or "a"


    FileTableEntry ( Inode i, short inumber, String m ) {
        seekPtr = 0;           // the seek pointer is set to the file top.
	    inode = i;
        iNumber = inumber;
        count = 1;           // at least one thread is using this entry.
        mode = convertModeToShort(m);            // once file access mode is set, it never changes.

	    if ( mode == 2 )
	       seekPtr = inode.length;
        }

    // A method that converts the string to an appropriate integer
    // Helps with checking and other logic throughout the files so we don't
    // have to keep comparing the string.
    public static short convertModeToShort(String m){
        m = m.toLowerCase();
        if(m.compareTo("w") == 0){
            return 0;
        }
        else if(m.compareTo("w+") == 0){
            return 1;
        }
        else if(m.compareTo("a") == 0){
            return 2;
        }
        else if(m.compareTo("r") == 0){
            return 3;
        }
        else{
            return -1;
        }
    }

}
