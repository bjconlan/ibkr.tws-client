package io.github.bjconlan.ibkr.protocol;

import io.github.bjconlan.ibkr.event.IbEvent;
import io.github.bjconlan.ibkr.proto.CurrentTimeProto;
import io.github.bjconlan.ibkr.proto.CurrentTimeRequestProto;
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
        byte[] frame = Encoder.encode(OutgoingId.REQ_CURRENT_TIME,
                CurrentTimeRequestProto.CurrentTimeRequest.getDefaultInstance());

        int length = Wire.readInt(frame, 0);
        int msgId = Wire.readInt(frame, 4);
        assertEquals(frame.length - 4, length);
        assertEquals(OutgoingId.REQ_CURRENT_TIME.id() + Wire.PROTOBUF_MSG_ID, msgId);
    }

    @Test
    void decodesTickPriceAsTypedProtobufPayload() {
        IbEvent event = decoder.decode(body(Wire.frame(IncomingId.TICK_PRICE,
                TickPriceProto.TickPrice.newBuilder()
                        .setReqId(7).setTickType(1).setPrice(123.45).setSize("100").setAttrMask(1)
                        .build()))).getFirst();

        assertEquals(IncomingId.TICK_PRICE.id(), assertInstanceOf(IbEvent.Message.class, event).id());
        TickPriceProto.TickPrice price = payload(event, TickPriceProto.TickPrice.class);
        assertEquals(7, price.getReqId());
        assertEquals(1, price.getTickType());
        assertEquals(123.45, price.getPrice());
        assertEquals("100", price.getSize());
        assertEquals(1, price.getAttrMask());
    }

    @Test
    void decodesHistoricalDataCarryingAllBars() {
        byte[] frame = Wire.frame(IncomingId.HISTORICAL_DATA, HistoricalDataProto.HistoricalData.newBuilder()
                .setReqId(3)
                .addHistoricalDataBars(HistoricalDataBarProto.HistoricalDataBar.newBuilder()
                        .setDate("20260910").setOpen(1).setHigh(2).setLow(0.5).setClose(1.5)
                        .setVolume("1000").setWAP("1.4").setBarCount(42))
                .addHistoricalDataBars(HistoricalDataBarProto.HistoricalDataBar.newBuilder()
                        .setDate("20260911").setOpen(1.5).setHigh(3).setLow(1).setClose(2))
                .build());

        List<IbEvent> events = decoder.decode(body(frame));

        assertEquals(1, events.size());
        HistoricalDataProto.HistoricalData data = payload(events.getFirst(), HistoricalDataProto.HistoricalData.class);
        assertEquals(3, data.getReqId());
        assertEquals(2, data.getHistoricalDataBarsCount());
        assertEquals("20260910", data.getHistoricalDataBars(0).getDate());
        assertEquals(42, data.getHistoricalDataBars(0).getBarCount());
    }

    @Test
    void decodesNextValidIdAndCurrentTime() {
        IbEvent next = decoder.decode(body(Wire.frame(IncomingId.NEXT_VALID_ID,
                NextValidIdProto.NextValidId.newBuilder().setOrderId(11).build()))).getFirst();
        IbEvent time = decoder.decode(body(Wire.frame(IncomingId.CURRENT_TIME,
                CurrentTimeProto.CurrentTime.newBuilder().setCurrentTime(1_700_000_000L).build()))).getFirst();

        assertEquals(11, payload(next, NextValidIdProto.NextValidId.class).getOrderId());
        assertEquals(1_700_000_000L, payload(time, CurrentTimeProto.CurrentTime.class).getCurrentTime());
    }

    private static <T> T payload(IbEvent event, Class<T> type) {
        return type.cast(assertInstanceOf(IbEvent.Message.class, event).payload());
    }

    /** Drops the outer 4-byte length prefix, leaving the {@code [msgId][payload]} body. */
    private static byte[] body(byte[] framed) {
        return Arrays.copyOfRange(framed, 4, framed.length);
    }
}
