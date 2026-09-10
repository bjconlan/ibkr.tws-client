package io.github.bjconlan.ibkr.protocol;

import io.github.bjconlan.ibkr.event.IbEvent;
import io.github.bjconlan.ibkr.proto.CurrentTimeProto;
import io.github.bjconlan.ibkr.proto.HistoricalDataBarProto;
import io.github.bjconlan.ibkr.proto.HistoricalDataProto;
import io.github.bjconlan.ibkr.proto.NextValidIdProto;
import io.github.bjconlan.ibkr.proto.TickPriceProto;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EncoderDecoderTest {

    private final Decoder decoder = new Decoder();

    @Test
    void connectHeaderIsApiPreambleThenLengthPrefixedVersion() {
        byte[] header = Encoder.connectHeader();

        assertEquals("API", new String(header, 0, 3, StandardCharsets.US_ASCII));
        assertEquals(0, header[3]);
        int versionLength = ByteBuffer.wrap(header, 4, 4).getInt();
        String version = new String(header, 8, versionLength, StandardCharsets.US_ASCII);
        assertEquals("v100..226", version);
        assertEquals(header.length, 8 + versionLength);
    }

    @Test
    void requestFrameCarriesLengthAndProtobufMessageId() {
        byte[] frame = Encoder.reqCurrentTime();

        int length = Wire.readInt(frame, 0);
        int msgId = Wire.readInt(frame, 4);
        assertEquals(frame.length - 4, length);
        assertEquals(OutgoingId.REQ_CURRENT_TIME.id() + Wire.PROTOBUF_MSG_ID, msgId);
    }

    @Test
    void decodesTickPriceIntoSealedEvent() {
        List<IbEvent> events = decoder.decode(body(Wire.frame(IncomingId.TICK_PRICE,
                TickPriceProto.TickPrice.newBuilder()
                        .setReqId(7).setTickType(1).setPrice(123.45).setSize("100").setAttrMask(1)
                        .build())));

        IbEvent.Tick.Price price = assertInstanceOf(IbEvent.Tick.Price.class, events.getFirst());
        assertEquals(7, price.requestId());
        assertEquals(1, price.tickType());
        assertEquals(123.45, price.price());
        assertEquals("100", price.size());
        assertTrue(price.attrib().canAutoExecute());
    }

    @Test
    void decodesHistoricalDataIntoOneEventPerBar() {
        byte[] frame = Wire.frame(IncomingId.HISTORICAL_DATA, HistoricalDataProto.HistoricalData.newBuilder()
                .setReqId(3)
                .addHistoricalDataBars(HistoricalDataBarProto.HistoricalDataBar.newBuilder()
                        .setDate("20260910").setOpen(1).setHigh(2).setLow(0.5).setClose(1.5)
                        .setVolume("1000").setWAP("1.4").setBarCount(42))
                .addHistoricalDataBars(HistoricalDataBarProto.HistoricalDataBar.newBuilder()
                        .setDate("20260911").setOpen(1.5).setHigh(3).setLow(1).setClose(2))
                .build());

        List<IbEvent> events = decoder.decode(body(frame));

        assertEquals(2, events.size());
        IbEvent.HistoricalBar first = assertInstanceOf(IbEvent.HistoricalBar.class, events.getFirst());
        assertEquals(3, first.requestId());
        assertEquals("20260910", first.bar().date());
        assertEquals(42, first.bar().barCount());
    }

    @Test
    void decodesNextValidIdAndCurrentTime() {
        IbEvent next = decoder.decode(body(Wire.frame(IncomingId.NEXT_VALID_ID,
                NextValidIdProto.NextValidId.newBuilder().setOrderId(11).build()))).getFirst();
        IbEvent time = decoder.decode(body(Wire.frame(IncomingId.CURRENT_TIME,
                CurrentTimeProto.CurrentTime.newBuilder().setCurrentTime(1_700_000_000L).build()))).getFirst();

        assertEquals(11, assertInstanceOf(IbEvent.NextValidId.class, next).orderId());
        assertEquals(1_700_000_000L, assertInstanceOf(IbEvent.CurrentTime.class, time).epochSeconds());
    }

    /** Drops the outer 4-byte length prefix, leaving the {@code [msgId][payload]} body. */
    private static byte[] body(byte[] framed) {
        return Arrays.copyOfRange(framed, 4, framed.length);
    }
}
