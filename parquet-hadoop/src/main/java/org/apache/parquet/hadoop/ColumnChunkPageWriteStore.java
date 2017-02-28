/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.parquet.hadoop;

import static java.lang.String.format;
import static org.apache.parquet.Log.INFO;
import static org.apache.parquet.column.statistics.Statistics.getStatsBasedOnType;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.parquet.Log;
import org.apache.parquet.bytes.ByteBufferAllocator;
import org.apache.parquet.bytes.BytesInput;
import org.apache.parquet.column.ColumnDescriptor;
import org.apache.parquet.column.Dictionary;
import org.apache.parquet.column.Encoding;
import org.apache.parquet.column.ParquetProperties;
import org.apache.parquet.column.SortedDictionary;
import org.apache.parquet.column.ValuesType;
import org.apache.parquet.column.page.DictionaryPage;
import org.apache.parquet.column.page.PageWriteStore;
import org.apache.parquet.column.page.PageWriter;
import org.apache.parquet.column.statistics.Statistics;
import org.apache.parquet.column.values.ValuesReader;
import org.apache.parquet.column.values.ValuesWriter;
import org.apache.parquet.column.values.dictionary.DictionaryValuesWriter;
import org.apache.parquet.column.values.dictionary.IntList;
import org.apache.parquet.format.PageHeader;
import org.apache.parquet.format.converter.ParquetMetadataConverter;
import org.apache.parquet.hadoop.CodecFactory.BytesCompressor;
import org.apache.parquet.hadoop.PageHolder.PageType;
import org.apache.parquet.io.ParquetEncodingException;
import org.apache.parquet.schema.MessageType;

class ColumnChunkPageWriteStore implements PageWriteStore {
  private static final Log LOG = Log.getLog(ColumnChunkPageWriteStore.class);

  private static ParquetMetadataConverter parquetMetadataConverter = new ParquetMetadataConverter();

  private static final class ColumnChunkPageWriter implements PageWriter {

    private final ColumnDescriptor path;
    private final BytesCompressor compressor;

    private final ByteArrayOutputStream tempOutputStream = new ByteArrayOutputStream();
    private DictionaryPage bufferedDictionaryPage;

    private long uncompressedLength;
    private long compressedLength;
    private long totalValueCount;
    private long bufferedSize;
    private int pageCount;
    private boolean dictionaryEncodingUsedForAllPages;

    // repetition and definition level encodings are used only for v1 pages and don't change
    private Set<Encoding> rlEncodings = new HashSet<Encoding>();
    private Set<Encoding> dlEncodings = new HashSet<Encoding>();
    private List<Encoding> dataEncodings = new ArrayList<Encoding>();

    private List<PageHolder> bufferedPages = new ArrayList<PageHolder>();
    private List<ByteBuffer> allocatedBuffers = new ArrayList<ByteBuffer>();

    private Statistics<?> totalStatistics;
    private ParquetProperties parquetProperties;

    private ColumnChunkPageWriter(ColumnDescriptor path,
                                  BytesCompressor compressor,
                                  ParquetProperties parquetProperties) {
      this.path = path;
      this.compressor = compressor;
      this.parquetProperties = parquetProperties;
      this.totalStatistics = getStatsBasedOnType(this.path.getType());
      this.dictionaryEncodingUsedForAllPages = true;
    }

    // Copy data on to byte buffer created using allocator
    BytesInput copy(BytesInput data) throws IOException {
      ByteBufferAllocator allocator = parquetProperties.getAllocator();
      final ByteBuffer byteBuffer = allocator.allocate((int)data.size());
      byteBuffer.put(data.toByteArray());
      allocatedBuffers.add(byteBuffer);
      return BytesInput.from(byteBuffer, 0, (int)data.size());
    }

    @Override
    public void writePage(BytesInput data,
                          int valueCount,
                          Statistics<?> statistics,
                          Encoding rlEncoding,
                          Encoding dlEncoding,
                          Encoding valuesEncoding) throws IOException {
      this.totalValueCount += valueCount;
      this.pageCount += 1;
      this.totalStatistics.mergeStatistics(statistics);
      long uncompressedSize = data.size();
      this.dictionaryEncodingUsedForAllPages &= valuesEncoding.usesDictionary();
      // if current page is dictionary encoded then do not compress as:
      // - we may have to rewrite it
      // - compression will be small anyway
      final boolean compressed = !valuesEncoding.usesDictionary();
      final BytesInput bytes = compressed ? compressor.compress(data) : data;
      this.bufferedSize += bytes.size();
      bufferedPages.add(new PageV1Holder(parquetProperties.getAllocator(), compressor, pageCount, path,
          bytes, valueCount, statistics, rlEncoding, dlEncoding, valuesEncoding, compressed, uncompressedSize));
    }

    private PageHeaderWithOffset preparePage(PageV1Holder pageV1Holder, long currentPos, List<BytesInput> out) throws IOException {

      final long uncompressedSize = pageV1Holder.getUncompressedPageBodySize();
      if (uncompressedSize > Integer.MAX_VALUE) {
        throw new ParquetEncodingException(
            "Cannot write page larger than Integer.MAX_VALUE bytes: " +
                uncompressedSize);
      }
      final BytesInput compressedBytes = BytesInput.from(pageV1Holder.getPageBody());
      final long compressedSize = compressedBytes.size();

      tempOutputStream.reset();
      final PageHeader pageHeader = parquetMetadataConverter.writeAndReturnDataPageHeader(
          (int)uncompressedSize,
          (int)compressedSize,
          pageV1Holder.getValueCount(),
          pageV1Holder.getStatistics(),
          pageV1Holder.getRlEncoding(),
          pageV1Holder.getDlEncoding(),
          pageV1Holder.getValuesEncoding(),
          tempOutputStream);
      this.uncompressedLength += uncompressedSize;
      this.compressedLength += compressedSize;
      // by concatenating before collecting instead of collecting twice,
      // we only allocate one buffer to copy into instead of multiple.
      out.add(BytesInput.from(tempOutputStream.toByteArray()));
      out.add(compressedBytes);
      rlEncodings.add(pageV1Holder.getRlEncoding());
      dlEncodings.add(pageV1Holder.getDlEncoding());
      dataEncodings.add(pageV1Holder.getValuesEncoding());
      return new PageHeaderWithOffset(pageHeader, currentPos + tempOutputStream.size());
    }

    @Override
    public void writePageV2(
        int rowCount, int nullCount, int valueCount,
        BytesInput repetitionLevels, BytesInput definitionLevels,
        Encoding dataEncoding, BytesInput data,
        Statistics<?> statistics) throws IOException {
      this.totalValueCount += valueCount;
      this.pageCount += 1;
      this.totalStatistics.mergeStatistics(statistics);
      int totalSize = toIntWithCheck(
        data.size() + repetitionLevels.size() + definitionLevels.size());
      this.bufferedSize += totalSize;
      boolean usesDictionary = dataEncoding.usesDictionary();
      this.dictionaryEncodingUsedForAllPages &= usesDictionary;
      final boolean compressed = !usesDictionary;
      final BytesInput bytes = compressed ? compressor.compress(data) : data;
      bufferedPages.add(new PageV2Holder(parquetProperties.getAllocator(), compressor, pageCount, path,
          rowCount, nullCount, valueCount, repetitionLevels, definitionLevels, dataEncoding, bytes,
          statistics, compressed, data.size()));
    }

    private PageHeaderWithOffset preparePage(PageV2Holder pageV2Holder, long currentPos, List<BytesInput> out) throws IOException {
      final BytesInput repetitionLevels = BytesInput.from(pageV2Holder.getRepetitionLevels());
      final BytesInput definitionLevels = BytesInput.from(pageV2Holder.getDefinitionLevels());

      int rlByteLength = toIntWithCheck(repetitionLevels.size());
      int dlByteLength = toIntWithCheck(definitionLevels.size());
      int uncompressedSize = toIntWithCheck(
          pageV2Holder.getUncompressedValuesSize() + repetitionLevels.size() + definitionLevels.size()
      );
      final BytesInput compressedData = BytesInput.from(pageV2Holder.getData());
      int compressedSize = toIntWithCheck(
          compressedData.size() + repetitionLevels.size() + definitionLevels.size()
      );
      tempOutputStream.reset();
      final PageHeader pageHeader = parquetMetadataConverter.writeAndReturnDataPageV2Header(
          uncompressedSize, compressedSize,
          pageV2Holder.getValueCount(),
          pageV2Holder.getNullCount(),
          pageV2Holder.getRowCount(),
          pageV2Holder.getStatistics(),
          pageV2Holder.getValuesEncoding(),
          rlByteLength,
          dlByteLength,
          tempOutputStream);
      this.uncompressedLength += uncompressedSize;
      this.compressedLength += compressedSize;

      // add to the output
      out.add(BytesInput.from(tempOutputStream.toByteArray()));
      out.add(repetitionLevels);
      out.add(definitionLevels);
      out.add(compressedData);
      dataEncodings.add(pageV2Holder.getValuesEncoding());
      return new PageHeaderWithOffset(pageHeader, currentPos + tempOutputStream.size());
    }

    private int toIntWithCheck(long size) {
      if (size > Integer.MAX_VALUE) {
        throw new ParquetEncodingException(
            "Cannot write page larger than " + Integer.MAX_VALUE + " bytes: " +
                size);
      }
      return (int)size;
    }

    @Override
    public long getMemSize() {
      return bufferedSize;
    }

    private void writeBufferedPages(ParquetFileWriter writer, DictionaryPage dictionaryPage) throws IOException {
      final List<PageHeaderWithOffset> pageHeaderWithOffsets = new ArrayList<PageHeaderWithOffset>();
      writer.startColumn(path, totalValueCount, compressor.getCodecName());
      if (dictionaryPage != null) {
        // compress dictionary page before writing
        writer.writeDictionaryPage(
          new DictionaryPage(compressor.compress(dictionaryPage.getBytes()),
            dictionaryPage.getUncompressedSize(),
            dictionaryPage.getDictionarySize(),
            dictionaryPage.getEncoding()),
            /* sorted */ true);
        dataEncodings.add(dictionaryPage.getEncoding());
      }

      List<BytesInput> buffers = new ArrayList<BytesInput>();
      // start from current offset in output file, until now page with offsets have saved page sizes.
      long pageOffset = writer.getPos();
      for (PageHolder bufferedPage : bufferedPages) {
        bufferedPage.compressIfNeeded();
        final PageHeaderWithOffset pageHeader;
        if (PageType.V1 == bufferedPage.getType()) {
          pageHeader = preparePage((PageV1Holder)bufferedPage, pageOffset, buffers);
        } else if (PageType.V2 == bufferedPage.getType()) {
          pageHeader = preparePage((PageV2Holder)bufferedPage, pageOffset, buffers);
        } else {
          throw new IOException("Invalid page type " + bufferedPage.getType());
        }
        pageHeaderWithOffsets.add(pageHeader);

        // add compressed size of this page to page offset which should be staring offset of the next page
        pageOffset = pageHeader.getOffset() + pageHeader.getPageHeader().getCompressed_page_size();
      }

      BytesInput outputBuffer = BytesInput.concat(buffers);
      long totalSize = outputBuffer.size();
      writer.writeDataPages(outputBuffer, uncompressedLength, compressedLength, totalStatistics, rlEncodings, dlEncodings, dataEncodings, pageHeaderWithOffsets);

      writer.endColumn();
      if (INFO) {
        LOG.info(
          String.format(
            "written %,dB for %s: %,d values, %,dB raw, %,dB comp, %d pages, encodings: %s",
            totalSize, path, totalValueCount, uncompressedLength, compressedLength, pageCount, new HashSet<Encoding>(dataEncodings))
            + (dictionaryPage != null ? String.format(
            ", dic { %,d entries, %,dB raw, %,dB comp}",
            dictionaryPage.getDictionarySize(), dictionaryPage.getUncompressedSize(), dictionaryPage.getDictionarySize())
            : ""));
      }
      for (PageHolder bufferedPage : bufferedPages) {
        bufferedPage.release();
      }
      for (ByteBuffer buffer:  allocatedBuffers) {
        parquetProperties.getAllocator().release(buffer);
      }
      rlEncodings.clear();
      dlEncodings.clear();
      dataEncodings.clear();
      pageCount = 0;
    }

    private void checkDictionaryEncoding() throws IOException {
      if (bufferedDictionaryPage != null && !dictionaryEncodingUsedForAllPages) {
        // Undo dictionary encoding if it's not used all the way
        final Dictionary dictionary = bufferedDictionaryPage.getEncoding().initDictionary(path, bufferedDictionaryPage);
        for (PageHolder pageHolder : bufferedPages) {
          Encoding valuesEncoding = pageHolder.getValuesEncoding();
          if (valuesEncoding.usesDictionary()) {
            final ValuesWriter valuesWriter =
                parquetProperties.newFallbackValuesWriter(path);
            final ValuesReader dictionaryBasedValuesReader =
                valuesEncoding.getDictionaryBasedValuesReader(path, ValuesType.VALUES, dictionary);
            final int pageDataOffset = pageHolder.getDataOffset();
            dictionaryBasedValuesReader.initFromPage(pageHolder.getValueCount(), pageHolder.getValuesBytes(), pageDataOffset);
            try {
              // read value from dictionary reader and write to plain/fallback value writer
              for (int i = 0; i < pageHolder.getNonNullValueCount(); ++i) {
                path.getType().copyFrom(dictionaryBasedValuesReader, valuesWriter);

              }
              // reset data and page encoding
              pageHolder.updateData(valuesWriter.getBytes(), valuesWriter.getEncoding());
            } finally {
              valuesWriter.close();
            }
          }
        }
        bufferedDictionaryPage = null;
      }
    }

    public void writeToFileWriter(ParquetFileWriter writer) throws IOException {
      checkDictionaryEncoding();

      DictionaryPage sortedDictionaryPage = bufferedDictionaryPage == null ? null : sortDictionary().getSortedDictionaryPage();

      writeBufferedPages(writer, sortedDictionaryPage);
    }

    private SortedDictionary sortDictionary() throws IOException {
      // Copy dictionary page and create a sorted dictionary
      final SortedDictionary sortedDictionary = new SortedDictionary(bufferedDictionaryPage, path, parquetProperties);
      // For each buffered page, read dictionary ids and map them to new ids.
      // Use dictionary writer to serialize newly encoded values to bytes
      for (PageHolder pageHolder : bufferedPages) {
        final ByteBuffer data = pageHolder.getValuesBytes();
        final Encoding valuesEncoding = pageHolder.getValuesEncoding();
        final ValuesReader dictionaryBasedValuesReader =
          valuesEncoding.getDictionaryBasedValuesReader(path, ValuesType.VALUES, sortedDictionary.getDictionary());
        dictionaryBasedValuesReader.initFromPage(pageHolder.getValueCount(), data, data.position());

        final DictionaryValuesWriter valuesWriter = parquetProperties.newDictionaryWriter(path);
        final IntList encodedValues = new IntList();
        try {
          for (int i = 0; i < pageHolder.getNonNullValueCount(); ++i) {
            final int oldDictionaryId = dictionaryBasedValuesReader.readValueDictionaryId();
            encodedValues.add(sortedDictionary.getNewId(oldDictionaryId));
          }
          BytesInput valuesBytes = valuesWriter.getBytes(encodedValues, sortedDictionary.getSize());
          pageHolder.updateData(valuesBytes, pageHolder.getValuesEncoding());
        } finally {
          valuesWriter.close();
        }
      }
      return sortedDictionary;
    }

    @Override
    public long allocatedSize() {
      return bufferedSize;
    }

    @Override
    public void writeDictionaryPage(DictionaryPage dictionaryPage) throws IOException {
      if (this.bufferedDictionaryPage != null) {
        throw new ParquetEncodingException("Only one dictionary page is allowed");
      }
      BytesInput dictionaryBytes = dictionaryPage.getBytes();
      int uncompressedSize = (int)dictionaryBytes.size();
      this.bufferedDictionaryPage = new DictionaryPage(copy(dictionaryBytes), uncompressedSize, dictionaryPage.getDictionarySize(), dictionaryPage.getEncoding());
    }

    @Override
    public String memUsageString(String prefix) {
      return format("ColumnChunkPageWriter: %d bytes. #pages = %s", bufferedSize, bufferedPages.size());
    }
  }

  private final Map<ColumnDescriptor, ColumnChunkPageWriter> writers = new HashMap<ColumnDescriptor, ColumnChunkPageWriter>();
  private final MessageType schema;

  public ColumnChunkPageWriteStore(BytesCompressor compressor, MessageType schema, ByteBufferAllocator allocator) {
    this(compressor, schema, ParquetProperties.builder().withAllocator(allocator).build());
  }

  public ColumnChunkPageWriteStore(BytesCompressor compressor, MessageType schema, ParquetProperties parquetProperties) {
    this.schema = schema;
    for (ColumnDescriptor path : schema.getColumns()) {
      writers.put(path,  new ColumnChunkPageWriter(path, compressor, parquetProperties));
    }
  }

  @Override
  public PageWriter getPageWriter(ColumnDescriptor path) {
    return writers.get(path);
  }

  public void flushToFileWriter(ParquetFileWriter writer) throws IOException {
    for (ColumnDescriptor path : schema.getColumns()) {
      ColumnChunkPageWriter pageWriter = writers.get(path);
      pageWriter.writeToFileWriter(writer);
    }
  }

}
