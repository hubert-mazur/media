/*
 * Copyright 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package androidx.media3.extractor.ts;

import static androidx.media3.extractor.ts.TsPayloadReader.FLAG_DATA_ALIGNMENT_INDICATOR;
import static androidx.media3.extractor.ts.TsPayloadReader.FLAG_RANDOM_ACCESS_INDICATOR;
import static java.lang.Math.min;
import static java.lang.annotation.ElementType.TYPE_USE;

import androidx.annotation.IntDef;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.ParserException;
import androidx.media3.common.util.Assertions;
import androidx.media3.common.util.ParsableBitArray;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import androidx.media3.extractor.ExtractorOutput;
import androidx.media3.extractor.MpeghUtil;
import androidx.media3.extractor.TrackOutput;
import com.google.common.collect.ImmutableList;
import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.List;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

/** Parses a continuous MPEG-H audio byte stream and extracts MPEG-H frames. */
@UnstableApi
public final class MpeghReader implements ElementaryStreamReader {

  @Documented
  @Retention(RetentionPolicy.SOURCE)
  @Target(TYPE_USE)
  @IntDef({STATE_FINDING_SYNC, STATE_READING_PACKET_HEADER, STATE_READING_PACKET_PAYLOAD})
  private @interface State {}

  private static final int STATE_FINDING_SYNC = 0;
  private static final int STATE_READING_PACKET_HEADER = 1;
  private static final int STATE_READING_PACKET_PAYLOAD = 2;

  private @State int state;

  private @MonotonicNonNull String formatId;
  private @MonotonicNonNull TrackOutput output;

  // The timestamp to attach to the next sample in the current packet.
  private double timeUs;
  private double timeUsPending;
  private boolean dataPending;
  private boolean rapPending;
  private @TsPayloadReader.Flags int flags;

  private int syncBytes;

  private final ParsableByteArray headerScratchBytes;
  private boolean headerDataFinished;

  private final ParsableByteArray dataScratchBytes;

  private int payloadBytesRead;
  private int frameBytes;

  @Nullable private MpeghUtil.MhasPacketHeader header;
  private int samplingRate;
  private int standardFrameLength;
  private int truncationSamples;
  private long mainStreamLabel;
  private boolean configFound;

  /** Constructs a new reader for MPEG-H elementary streams. */
  public MpeghReader() {
    state = STATE_FINDING_SYNC;
    headerScratchBytes = new ParsableByteArray(new byte[MpeghUtil.MAX_MHAS_PACKET_HEADER_SIZE]);
    dataScratchBytes = new ParsableByteArray();
    samplingRate = C.RATE_UNSET_INT;
    standardFrameLength = C.LENGTH_UNSET;
    mainStreamLabel = C.INDEX_UNSET;
    rapPending = true;
    timeUs = C.TIME_UNSET;
    timeUsPending = C.TIME_UNSET;
  }

  @Override
  public void seek() {
    state = STATE_FINDING_SYNC;
    syncBytes = 0;
    headerScratchBytes.setPosition(0);
    dataScratchBytes.setPosition(0);
    dataScratchBytes.setLimit(0);
    header = null;
    headerDataFinished = false;
    payloadBytesRead = 0;
    frameBytes = 0;
    samplingRate = C.RATE_UNSET_INT;
    standardFrameLength = C.LENGTH_UNSET;
    truncationSamples = 0;
    mainStreamLabel = C.INDEX_UNSET;
    configFound = false;
    dataPending = false;
    rapPending = true;
    timeUs = C.TIME_UNSET;
    timeUsPending = C.TIME_UNSET;
  }

  @Override
  public void createTracks(
      ExtractorOutput extractorOutput, TsPayloadReader.TrackIdGenerator idGenerator) {
    idGenerator.generateNewId();
    formatId = idGenerator.getFormatId();
    output = extractorOutput.track(idGenerator.getTrackId(), C.TRACK_TYPE_AUDIO);
  }

  @Override
  public void packetStarted(long pesTimeUs, @TsPayloadReader.Flags int flags) {
    this.flags = flags;

    if (!rapPending && (frameBytes != 0 || !headerDataFinished)) {
      dataPending = true;
    }

    if (pesTimeUs != C.TIME_UNSET) {
      if (dataPending) {
        timeUsPending = pesTimeUs;
      } else {
        timeUs = pesTimeUs;
      }
    }
  }

  @Override
  public void consume(ParsableByteArray data) throws ParserException {
    Assertions.checkStateNotNull(output); // Asserts that createTracks has been called.

    int headerDataPos;
    ParsableBitArray bitArray = new ParsableBitArray();
    while (data.bytesLeft() > 0) {
      switch (state) {
        case STATE_FINDING_SYNC:
          if (skipToNextSync(data)) {
            state = STATE_READING_PACKET_HEADER;
          }
          break;
        case STATE_READING_PACKET_HEADER:
          // check if the gathering of data in header scratch buffer was finished and adjust
          // remaining bytes
          if (headerDataFinished && headerScratchBytes.getPosition() > 0) {
            System.arraycopy(
                headerScratchBytes.getData(),
                headerScratchBytes.getPosition(),
                headerScratchBytes.getData(),
                0,
                headerScratchBytes.bytesLeft());
            headerScratchBytes.setPosition(headerScratchBytes.bytesLeft());
            headerDataFinished = false;
          }

          // read into header scratch buffer
          if (continueRead(data, headerScratchBytes, MpeghUtil.MAX_MHAS_PACKET_HEADER_SIZE)) {
            // make the scratch bytes available for parsing
            headerScratchBytes.setPosition(0);
            bitArray.reset(headerScratchBytes);

            // parse the MHAS packet header
            header = MpeghUtil.parseMhasPacketHeader(bitArray);

            // write the packet header to output
            output.sampleData(headerScratchBytes, header.headerLength);

            payloadBytesRead = 0;
            frameBytes += header.packetLength + header.headerLength;

            if (header.packetType == MpeghUtil.MhasPacketHeader.PACTYP_AUDIOTRUNCATION
                || header.packetType == MpeghUtil.MhasPacketHeader.PACTYP_MPEGH3DACFG) {
              dataScratchBytes.ensureCapacity(header.packetLength);
              dataScratchBytes.setPosition(0);
              dataScratchBytes.setLimit(header.packetLength);
            }
            // MHAS packet header finished -> obtain the packet payload
            state = STATE_READING_PACKET_PAYLOAD;
            headerDataFinished = true;
          }
          break;
        case STATE_READING_PACKET_PAYLOAD:
          if (header.packetType == MpeghUtil.MhasPacketHeader.PACTYP_MPEGH3DACFG
              || header.packetType == MpeghUtil.MhasPacketHeader.PACTYP_AUDIOTRUNCATION) {
            // read bytes from header scratch buffer into the data scratch buffer
            headerDataPos = headerScratchBytes.getPosition();
            if (headerDataPos != MpeghUtil.MAX_MHAS_PACKET_HEADER_SIZE) {
              continueRead(headerScratchBytes, dataScratchBytes, header.packetLength);
            }
            headerScratchBytes.setPosition(headerDataPos);
            // read bytes from input data into the data scratch buffer
            int dataStartPos = data.getPosition();
            continueRead(data, dataScratchBytes, header.packetLength);
            data.setPosition(dataStartPos);
          }

          int bytesToRead;
          // read bytes from header scratch buffer and write them into the output
          headerDataPos = headerScratchBytes.getPosition();
          if (headerDataPos != MpeghUtil.MAX_MHAS_PACKET_HEADER_SIZE) {
            bytesToRead =
                min(headerScratchBytes.bytesLeft(), header.packetLength - payloadBytesRead);
            output.sampleData(headerScratchBytes, bytesToRead);
            payloadBytesRead += bytesToRead;
          }
          // read bytes from input data and write them into the output
          bytesToRead = min(data.bytesLeft(), header.packetLength - payloadBytesRead);
          output.sampleData(data, bytesToRead);
          payloadBytesRead += bytesToRead;

          if (payloadBytesRead == header.packetLength) {
            dataScratchBytes.setPosition(0);
            bitArray.reset(dataScratchBytes);
            if (header.packetType == MpeghUtil.MhasPacketHeader.PACTYP_MPEGH3DACFG) {
              MpeghUtil.Mpegh3daConfig config = MpeghUtil.parseMpegh3daConfig(bitArray);
              samplingRate = config.samplingFrequency;
              standardFrameLength = config.standardFrameSamples;
              if (mainStreamLabel != header.packetLabel) {
                mainStreamLabel = header.packetLabel;
                // set the output format
                String codecs = "mhm1";
                if (config.profileLevelIndication != C.INDEX_UNSET) {
                  codecs += String.format(".%02X", config.profileLevelIndication);
                }
                @Nullable List<byte[]> initializationData = null;
                if (config.compatibleProfileLevelSet != null
                    && config.compatibleProfileLevelSet.length > 0) {
                  // The first entry in initializationData is reserved for the audio specific
                  // config.
                  initializationData =
                      ImmutableList.of(Util.EMPTY_BYTE_ARRAY, config.compatibleProfileLevelSet);
                }
                Format format =
                    new Format.Builder()
                        .setId(formatId)
                        .setSampleMimeType(MimeTypes.AUDIO_MPEGH_MHM1)
                        .setSampleRate(samplingRate)
                        .setCodecs(codecs)
                        .setInitializationData(initializationData)
                        .build();
                output.format(format);
              }
              configFound = true;
            } else if (header.packetType == MpeghUtil.MhasPacketHeader.PACTYP_AUDIOTRUNCATION) {
              truncationSamples = MpeghUtil.parseAudioTruncationInfo(bitArray);
            } else if (header.packetType == MpeghUtil.MhasPacketHeader.PACTYP_MPEGH3DAFRAME) {
              @C.BufferFlags int flag = 0;
              // if we have a frame with an mpegh3daConfig, set the first obtained AU to a key frame
              if (configFound) {
                flag = C.BUFFER_FLAG_KEY_FRAME;
                rapPending = false;
              }
              double sampleDurationUs =
                  (double) C.MICROS_PER_SECOND
                      * (standardFrameLength - truncationSamples)
                      / samplingRate;
              long pts = Math.round(timeUs);
              if (dataPending) {
                dataPending = false;
                timeUs = timeUsPending;
              } else {
                timeUs += sampleDurationUs;
              }
              output.sampleMetadata(pts, flag, frameBytes, 0, null);
              configFound = false;
              truncationSamples = 0;
              frameBytes = 0;
            }
            header = null;
            // MHAS packet payload finished -> obtain a new packet header
            state = STATE_READING_PACKET_HEADER;
          }
          break;
        default:
          throw new IllegalStateException();
      }
    }
  }

  @Override
  public void packetFinished(boolean isEndOfInput) {
    // Do nothing.
  }

  /**
   * Continues a read from the provided {@code source} into a given {@code target}.
   *
   * @param source The source from which to read.
   * @param target The target into which data is to be read.
   * @param targetLength The target length of the read.
   * @return Whether the target length was reached.
   */
  private boolean continueRead(
      ParsableByteArray source, ParsableByteArray target, int targetLength) {
    int bytesToRead = min(source.bytesLeft(), targetLength - target.getPosition());
    source.readBytes(target.getData(), target.getPosition(), bytesToRead);
    target.setPosition(target.getPosition() + bytesToRead);
    return target.getPosition() == targetLength;
  }

  /**
   * Locates the next SYNC value in the buffer, advancing the position to the byte that immediately
   * follows it. If SYNC was not located, the position is advanced to the limit.
   *
   * @param pesBuffer The buffer whose position should be advanced.
   * @return Whether SYNC was found.
   */
  private boolean skipToNextSync(ParsableByteArray pesBuffer) {
    // we are still waiting for a RAP frame
    if (rapPending) {
      if ((flags & FLAG_RANDOM_ACCESS_INDICATOR) == 0) {
        // RAI is not signalled -> drop the PES data
        pesBuffer.setPosition(pesBuffer.limit());
        return false;
      }

      if ((flags & FLAG_DATA_ALIGNMENT_INDICATOR) == 0) {
        // if RAI is signalled but the data is not aligned we need to find the sync packet
        while (pesBuffer.bytesLeft() > 0) {
          syncBytes <<= C.BITS_PER_BYTE;
          syncBytes |= pesBuffer.readUnsignedByte();
          if (MpeghUtil.isSyncWord(syncBytes)) {
            pesBuffer.setPosition(pesBuffer.getPosition() - MpeghUtil.MHAS_SYNC_WORD_LENGTH);
            syncBytes = 0;
            return true;
          }
        }
      } else {
        return true;
      }
    } else {
      pesBuffer.setPosition(pesBuffer.limit());
    }
    return false;
  }
}
