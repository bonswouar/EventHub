package com.codecademy.eventhub.index;

import com.google.common.cache.LoadingCache;
import com.codecademy.eventhub.base.ByteBufferUtil;
import com.codecademy.eventhub.list.DmaList;

import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * UserEventIndex is responsible for indexing events sharded by users.
 */
public class UserEventIndex implements Closeable {
  public static final int POINTER_SIZE = 8; // 8 bytes
  public static final int ID_SIZE = 8; // 8 bytes

  private final DmaList<IndexEntry> index;
  private final IndexEntry.Factory indexEntryFactory;
  private final Block.Factory blockFactory;

  public UserEventIndex(DmaList<IndexEntry> index,
      IndexEntry.Factory indexEntryFactory, Block.Factory blockFactory) {
    this.index = index;
    this.indexEntryFactory = indexEntryFactory;
    this.blockFactory = blockFactory;
  }

  public int getEventOffset(int userId, long eventId) {
    IndexEntry indexEntry = index.get(userId);
    if (eventId <= indexEntry.getMinId()) {
      return 0;
    }

    int numPointersPerIndexEntry = indexEntryFactory.getNumPointers();
    int numRecordsPerBlock = blockFactory.getNumRecordsPerBlock();
    int numRecords = indexEntry.getNumRecords();
    if (numRecords == 0) {
    	// UserId without events ?! Shouldn't go there !
    	return 0;
    }
    int numBlocks = (int) Math.ceil((double) numRecords / numRecordsPerBlock);
    long minIdInIndex = indexEntry.getMinIdInIndex(Math.min(numBlocks, numPointersPerIndexEntry) - 1);
    if (eventId >= minIdInIndex) { // all blocks are in index
      for (int i = 0; i < numBlocks; i++) {
        if (eventId >= indexEntry.getMinIdInIndex(i)) {
          Block block = blockFactory.find(indexEntry.getPointer(i));
          return block.findOffset(eventId) + block.getMetaData().getBlockOffset() * numRecordsPerBlock;
        }
      }
    } else {
      Block block = blockFactory.find(blockFactory.find(
          indexEntry.getPointer(numPointersPerIndexEntry - 1)).getMetaData().getPrevBlockPointer());
      while (block != null) {
        if (eventId >= block.getMetaData().getMinId()) {
          return block.findOffset(eventId) + block.getMetaData().getBlockOffset() * numRecordsPerBlock;
        }
        block = blockFactory.find(block.getMetaData().getPrevBlockPointer());
      }
    }
    throw new IllegalStateException("shouldn't even reach here!!");
  }

  public void enumerateEventIds(int userId, int recordOffset, int maxRecords,
      UserEventIndex.Callback callback) {
    IndexEntry indexEntry = index.get(userId);
    maxRecords = Math.min(maxRecords, indexEntry.getNumRecords() - recordOffset);
    if (maxRecords <= 0) {
      return;
    }

    int numRecordsPerBlock = blockFactory.getNumRecordsPerBlock();
    int blockOffset = recordOffset / numRecordsPerBlock;
    Block block = findBlock(indexEntry, blockOffset);

    int offsetInCurrentBlock = recordOffset % numRecordsPerBlock;
    for (int i = 0; i < maxRecords; i++) {
      if (offsetInCurrentBlock >= numRecordsPerBlock) {
        block = blockFactory.find(block.getMetaData().getNextBlockPointer());
        offsetInCurrentBlock = 0;
      }
      // TODO: extract an iterator and fetch ahead?
      if (!callback.shouldContinueOnEventId(block.getRecord(offsetInCurrentBlock))) {
        return;
      }
      offsetInCurrentBlock++;
    }
  }

  public ArrayList<Long> remapEventIds(int oldUserId, int recordOffset, int originalMaxRecords, int newUserId, int newRecordOffset) {
	  // TODO : Remove old userId UserEvents ?
	  // Get oldUserId Events
	    ArrayList<Long> eventIds = new ArrayList<Long>();
	    IndexEntry indexEntry = index.get(oldUserId);
	    int maxRecords = Math.min(originalMaxRecords, indexEntry.getNumRecords() - recordOffset);
	    if (maxRecords <= 0) {
	      return eventIds;
	    }

	    int numRecordsPerBlock = blockFactory.getNumRecordsPerBlock();
	    int blockOffset = recordOffset / numRecordsPerBlock;
	    Block block = findBlock(indexEntry, blockOffset);

	    int offsetInCurrentBlock = recordOffset % numRecordsPerBlock;

	    for (int i = 0; i < maxRecords; i++) {
	      if (offsetInCurrentBlock >= numRecordsPerBlock) {
	        block = blockFactory.find(block.getMetaData().getNextBlockPointer());
	        offsetInCurrentBlock = 0;
	      }
	      eventIds.add(block.getRecord(offsetInCurrentBlock));
//	      // TODO: extract an iterator and fetch ahead?
//	      if (!callback.shouldContinueOnEventId(block.getRecord(offsetInCurrentBlock))) {
//	        return;
//	      }
	      offsetInCurrentBlock++;
	    }
	    
	    // Get newUserId block
	    IndexEntry newIndexEntry = index.get(newUserId);
	    maxRecords = Math.min(originalMaxRecords, newIndexEntry.getNumRecords() - newRecordOffset);
	    if (maxRecords <= 0) {
	      return new ArrayList<Long>();
	    }

//	    int newBlockOffset = newRecordOffset / numRecordsPerBlock;
//	    Block newBlock = findBlock(indexEntry, newBlockOffset);
	    // ICI insertEventId
//	      Block newBlock = blockFactory.build(0, eventIds.get(0));
	    for (long newEventId: eventIds) {
	    	maxRecords = newIndexEntry.getNumRecords();
		    insertEventId(newUserId, newEventId, newIndexEntry, maxRecords);
	    }
	    
	    // Remove old IndexEntry User
	        indexEntry = index.get(oldUserId);
	        indexEntry = indexEntryFactory.build();
	        indexEntry.setNumRecords(0);
//	        indexEntry.setMinId(99999);
	        indexEntry.shiftBlock(block);
	      index.update(oldUserId, indexEntry);

	    // "marchait" avant
//        if (newIndexEntry.getNumRecords() % numRecordsPerBlock == 0) { // need a new block
//            Block newBlock = blockFactory.build(newBlockOffset, eventIds.get(0));
//            Block prevBlock = findBlock(newIndexEntry, newBlockOffset - 1);
//            newBlock.getMetaData().setPrevBlockPointer(prevBlock.getMetaData().getPointer());
//            prevBlock.getMetaData().setNextBlockPointer(newBlock.getMetaData().getPointer());
//
//            newIndexEntry.shiftBlock(newBlock);
//          } else {
//            Block newBlock = findBlock(newIndexEntry, newBlockOffset);
//            newBlock.add(eventIds.get(0));
//          }
//        newIndexEntry.incrementNumRecord();
//        index.update(newUserId, newIndexEntry);
	      return eventIds;
	  }
  
  public void insertEventId(int userId, long eventId, IndexEntry indexEntry, int maxRecords) {
	    int numRecordsPerBlock = blockFactory.getNumRecordsPerBlock();
	    int blockOffset = 0;
	    Block block = findBlock(indexEntry, blockOffset);

	    int offsetInCurrentBlock = 0;

	    // Get events to add after
	    ArrayList<Long> eventIds = new ArrayList<Long>();

	    for (int i = 0; i < maxRecords; i++) {
		      if (offsetInCurrentBlock >= numRecordsPerBlock) {
		        block = blockFactory.find(block.getMetaData().getNextBlockPointer());
		        offsetInCurrentBlock = 0;
		      }
		      if (block.getRecord(offsetInCurrentBlock) > eventId) {
		    	  eventIds.add(block.getRecord(offsetInCurrentBlock));
		      }
		      offsetInCurrentBlock++;
		    }
	    
	    // Ca marche ça ?
	    if (eventIds.isEmpty()) {
	    	  insertLastEvent(indexEntry, block, numRecordsPerBlock, eventId, offsetInCurrentBlock);
	    } else {
		    blockOffset = 0;
		    block = findBlock(indexEntry, blockOffset);
	
		    offsetInCurrentBlock = 0;
		    // Add New Event
		    for (int i = 0; i < maxRecords; i++) {
			      if (offsetInCurrentBlock >= numRecordsPerBlock) {
			        block = blockFactory.find(block.getMetaData().getNextBlockPointer());
			        offsetInCurrentBlock = 0;
			      }
			      // Insert at the right position
			      if (block.getRecord(offsetInCurrentBlock) > eventId) {
			    	  insertLastEvent(indexEntry, block, numRecordsPerBlock, eventId, offsetInCurrentBlock);
			      }
			      offsetInCurrentBlock++;
			    }
		    // Add old Events
		    for (long oldEventId: eventIds) {
		    	  insertLastEvent(indexEntry, block, numRecordsPerBlock, oldEventId, offsetInCurrentBlock);
			      offsetInCurrentBlock++;
		    }
	    }
	    block.incrementNumRecords();
	    indexEntry.incrementNumRecord();
	    index.update(userId, indexEntry);
  }
  
  public void insertLastEvent(IndexEntry indexEntry, Block newBlock, int numRecordsPerBlock, long eventId, int newRecordOffset) {
	  int newBlockOffset = newRecordOffset / numRecordsPerBlock;
      if (indexEntry.getNumRecords() % numRecordsPerBlock == 0) { // need a new block
//          Block newBlock = blockFactory.build(newBlockOffset, eventId);
          Block prevBlock = findBlock(indexEntry, newBlockOffset - 1);
          newBlock.getMetaData().setPrevBlockPointer(prevBlock.getMetaData().getPointer());
          prevBlock.getMetaData().setNextBlockPointer(newBlock.getMetaData().getPointer());

          indexEntry.shiftBlock(newBlock);
        } else {
//          Block newBlock = findBlock(indexEntry, 1);
        	newBlock.set(eventId, newRecordOffset);
        }
  }
  
  public synchronized void addEvent(int userId, long eventId) {
    IndexEntry indexEntry;
    long maxId = index.getMaxId();
    if (userId > maxId) {
      Block block = blockFactory.build(0, eventId);
      indexEntry = indexEntryFactory.build();
      indexEntry.setMinId(eventId);
      indexEntry.shiftBlock(block);
    } else {
      indexEntry = index.get(userId);
      int numRecords = indexEntry.getNumRecords();
      // this is more or less a hack, it relies on MappedByteBuffer to zeroes the buffer when initialized
      // which is an undefined behavior in the spec but implemented so in openjdk.
      if (numRecords == 0) {
        Block block = blockFactory.build(0, eventId);
        indexEntry = indexEntryFactory.build();
        indexEntry.setMinId(eventId);
        indexEntry.shiftBlock(block);
      } else {
        int numRecordsPerBlock = blockFactory.getNumRecordsPerBlock();
        int blockOffset = numRecords / numRecordsPerBlock;
        if (numRecords % numRecordsPerBlock == 0) { // need a new block
          Block block = blockFactory.build(blockOffset, eventId);
          Block prevBlock = findBlock(indexEntry, blockOffset - 1);
          block.getMetaData().setPrevBlockPointer(prevBlock.getMetaData().getPointer());
          prevBlock.getMetaData().setNextBlockPointer(block.getMetaData().getPointer());

          indexEntry.shiftBlock(block);
        } else {
          Block block = findBlock(indexEntry, blockOffset);
          block.add(eventId);
        }
      }
    }
    indexEntry.incrementNumRecord();
    index.update(userId, indexEntry);
  }

  @Override
  public void close() throws IOException {
    index.close();
    blockFactory.close();
  }

  public String getVarz(int indentation) {
    // TODO
    String indent  = new String(new char[indentation]).replace('\0', ' ');
    return String.format(
        indent + this.getClass().getName() + "\n" +
            indent + "==================\n" +
            indent + "index: %s\n",
        index.getVarz(indentation + 1)
    );
  }

  private Block findBlock(IndexEntry indexEntry, int blockOffset) {
    int numPointersPerIndexEntry = indexEntryFactory.getNumPointers();
    int numRecords = indexEntry.getNumRecords();
    int numRecordsPerBlock = blockFactory.getNumRecordsPerBlock();
    int numBlocks = (int) Math.ceil((double) numRecords / numRecordsPerBlock);
    if (blockOffset >= numBlocks - numPointersPerIndexEntry) { // current block offset is in index
      return blockFactory.find(indexEntry.getPointer(numBlocks - blockOffset - 1));
    } else {
      Block block = blockFactory.find(indexEntry.getPointer(numPointersPerIndexEntry - 1));
      for (int i = numBlocks - numPointersPerIndexEntry; i > blockOffset ; i--) {
        block = blockFactory.find(block.getMetaData().getPrevBlockPointer());
      }
      return block;
    }
  }

  public static class Block {
    private final MetaData metaData;
    private final ByteBuffer byteBuffer;

    public Block(MetaData metaData, ByteBuffer byteBuffer) {
      this.metaData = metaData;
      this.byteBuffer = byteBuffer;
    }

    public void add(long record) {
      int recordOffset = metaData.getNumRecordsAndIncrement();
      byteBuffer.putLong(recordOffset * ID_SIZE, record);
    }
    
    public void incrementNumRecords() {
	 metaData.incrementNumRecords();
    }

    public void set(long record, int recordOffset) {
      byteBuffer.putLong(recordOffset * ID_SIZE, record);
    }
    
    public long getRecord(int offsetInCurrentBlock) {
      return byteBuffer.getLong(offsetInCurrentBlock * ID_SIZE);
    }

    public MetaData getMetaData() {
      return metaData;
    }

    public int findOffset(long id) {
      return ByteBufferUtil.binarySearchOffset(byteBuffer, 0, metaData.getNumRecords(), id, ID_SIZE);
    }

    public static class MetaData {
      public static final int SIZE = 40;

      private final ByteBuffer byteBuffer;

      public MetaData(ByteBuffer byteBuffer) {
        this.byteBuffer = byteBuffer;
      }

      public int getBlockOffset() {
        return byteBuffer.getInt(0);
      }

      public void setBlockOffset(int blockOffset) {
        byteBuffer.putInt(0, blockOffset);
      }

      public int getNumRecords() {
        return byteBuffer.getInt(4);
      }

      public void setNumRecords(int numRecords) {
        byteBuffer.putInt(4, numRecords);
      }

      public synchronized int getNumRecordsAndIncrement() {
        int numRecords = getNumRecords();
        byteBuffer.putInt(4, numRecords + 1);
        return numRecords;
      }

      public synchronized void incrementNumRecords() {
        int numRecords = getNumRecords();
        byteBuffer.putInt(4, numRecords + 1);
      }
      
      public long getPointer() {
        return byteBuffer.getLong(8);
      }

      public void setPointer(long pointer) {
        byteBuffer.putLong(8, pointer);
      }

      public long getMinId() {
        return byteBuffer.getLong(16);
      }

      public void setMinId(long minId) {
        byteBuffer.putLong(16, minId);
      }

      public long getPrevBlockPointer() {
        return byteBuffer.getLong(24);
      }

      public void setPrevBlockPointer(long prevBlockPointer) {
        byteBuffer.putLong(24, prevBlockPointer);
      }

      public long getNextBlockPointer() {
        return byteBuffer.getLong(32);
      }

      public void setNextBlockPointer(long nextBlockPointer) {
        byteBuffer.putLong(32, nextBlockPointer);
      }
    }

    public static class Factory implements Closeable {
      private final String filename;
      private final int numBlocksPerFile;
      private long currentPointer;
      private final LoadingCache<Integer, MappedByteBuffer> buffers;
      private final int numRecordsPerBlock;

      public Factory(String filename, LoadingCache<Integer, MappedByteBuffer> buffers,
          int numRecordsPerBlock, int numBlocksPerFile, long currentPointer) {
        this.filename = filename;
        this.buffers = buffers;
        this.numRecordsPerBlock = numRecordsPerBlock;
        this.numBlocksPerFile = numBlocksPerFile;
        this.currentPointer = currentPointer;
      }

      public int getNumRecordsPerBlock() {
        return numRecordsPerBlock;
      }

      public Block find(long pointer) {
        final int fileSize = numBlocksPerFile * (
            numRecordsPerBlock * UserEventIndex.ID_SIZE + UserEventIndex.Block.MetaData.SIZE);
        MappedByteBuffer byteBuffer = buffers.getUnchecked((int) (pointer / fileSize));
        ByteBuffer metaDataByteBuffer = byteBuffer.duplicate();
        metaDataByteBuffer.position((int) (pointer % fileSize));
        metaDataByteBuffer = metaDataByteBuffer.slice();
        ByteBuffer blockByteBuffer = byteBuffer.duplicate();
        blockByteBuffer.position((int) (pointer % fileSize) + Block.MetaData.SIZE);
        blockByteBuffer = blockByteBuffer.slice();
        return new Block(new Block.MetaData(metaDataByteBuffer), blockByteBuffer);
      }

      public synchronized Block build(int blockOffset, long id) {
        final int fileSize = numBlocksPerFile * (
            numRecordsPerBlock * UserEventIndex.ID_SIZE + UserEventIndex.Block.MetaData.SIZE);
        long pointer = currentPointer;
        MappedByteBuffer byteBuffer = buffers.getUnchecked((int) (pointer / fileSize));
        int blockSize = numRecordsPerBlock * ID_SIZE + MetaData.SIZE;
        currentPointer += blockSize;
        ByteBuffer metaDataByteBuffer = byteBuffer.duplicate();
        metaDataByteBuffer.position((int) (pointer % fileSize));
        metaDataByteBuffer = metaDataByteBuffer.slice();
        ByteBuffer blockByteBuffer = byteBuffer.duplicate();
        blockByteBuffer.position((int) (pointer % fileSize) + Block.MetaData.SIZE);
        blockByteBuffer = blockByteBuffer.slice();

        Block.MetaData metaData = new Block.MetaData(metaDataByteBuffer);
        metaData.setBlockOffset(blockOffset);
        metaData.setPointer(pointer);
        metaData.setMinId(id);

        Block block = new Block(metaData, blockByteBuffer);
        block.add(id);

        return block;
      }

      @Override
      public void close() throws IOException {
        //noinspection ResultOfMethodCallIgnored
        new File(filename).getParentFile().mkdirs();
        try (ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(filename))) {
          oos.writeLong(currentPointer);
        }
        buffers.invalidateAll();
      }
    }
  }

  public static class IndexEntry {
    private AtomicInteger numRecords;
    private final long[] pointers;
    private final long[] minIds;
    private long minId;

    public IndexEntry(AtomicInteger numRecords, long minId, long[] pointers, long[] minIds) {
      this.numRecords = numRecords;
      this.minId = minId;
      this.pointers = pointers;
      this.minIds = minIds;
    }
    
    public void setNumRecords(int numRecords) {
        this.numRecords.set(0);;
    }

    public long getPointer(int i) {
      return pointers[i];
    }

    public long getMinIdInIndex(int i) {
      return minIds[i];
    }

    public int getNumRecords() {
      return numRecords.get();
    }

    public void incrementNumRecord() {
      numRecords.incrementAndGet();
    }

    public long getMinId() {
      return minId;
    }

    public void setMinId(long minId) {
      this.minId = minId;
    }

    public void shiftBlock(Block block) {
      for (int i = Math.min(block.getMetaData().getBlockOffset(), pointers.length - 1); i > 0; i--) {
        pointers[i] = pointers[i - 1];
        minIds[i] = minIds[i - 1];
      }
      pointers[0] = block.getMetaData().getPointer();
      minIds[0] = block.getMetaData().getMinId();
    }

    public static class Schema implements com.codecademy.eventhub.base.Schema<IndexEntry> {
      private final int numPointers;

      public Schema(int numPointers) {
        this.numPointers = numPointers;
      }

      @Override
      public int getObjectSize() {
        return numPointers * POINTER_SIZE + numPointers * ID_SIZE + 12;
      }

      @Override
      public byte[] toBytes(IndexEntry indexEntry) {
        ByteBuffer byteBuffer = ByteBuffer.allocate(getObjectSize());
        byteBuffer.putInt(indexEntry.getNumRecords());
        byteBuffer.putLong(indexEntry.getMinId());
        for (int i = 0; i < numPointers; i++) {
          byteBuffer.putLong(indexEntry.getPointer(i));
          byteBuffer.putLong(indexEntry.getMinIdInIndex(i));
        }
        return byteBuffer.array();
      }

      @Override
      public IndexEntry fromBytes(byte[] bytes) {
        ByteBuffer byteBuffer = ByteBuffer.wrap(bytes);
        long[] pointers = new long[numPointers];
        long[] minIds = new long[numPointers];
        int numRecords = byteBuffer.getInt();
        long minId = byteBuffer.getLong();
        for (int i = 0; i < numPointers; i++) {
          pointers[i] = byteBuffer.getLong();
          minIds[i] = byteBuffer.getLong();
        }
        return new IndexEntry(new AtomicInteger(numRecords), minId, pointers, minIds);
      }
    }

    public static class Factory {
      private final int numPointers;

      public Factory(int numPointers) {
        this.numPointers = numPointers;
      }

      public int getNumPointers() {
        return numPointers;
      }

      public IndexEntry build() {
        long[] pointers = new long[numPointers];
        long[] minIds = new long[numPointers];
        return new UserEventIndex.IndexEntry(new AtomicInteger(0), -1, pointers, minIds);
      }
    }
  }

  public static interface Callback {
    // return shouldContinue
    public boolean shouldContinueOnEventId(long eventId);
  }
}
