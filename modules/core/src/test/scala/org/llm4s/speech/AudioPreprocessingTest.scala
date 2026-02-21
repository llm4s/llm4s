package org.llm4s.speech

import org.scalatest.funsuite.AnyFunSuite
import org.llm4s.speech.processing.AudioPreprocessing

class AudioPreprocessingTest extends AnyFunSuite {

  test("toMono keeps mono identical") {
    val bytes = Array[Byte](0, 0, 1, 0, -1, -1, 0, 0)
    val meta  = AudioMeta(sampleRate = 16000, numChannels = 1, bitDepth = 16)
    val out   = AudioPreprocessing.toMono(bytes, meta)
    assert(out.exists(_._1.sameElements(bytes)))
    assert(out.exists(_._2.numChannels == 1))
  }

  test("toMono averages stereo channels into 2-byte little-endian samples") {
    // Two stereo frames: frame0 = (L=0x0100, R=0x0200), frame1 = (L=0x0010, R=0x0020)
    // PCM16 little-endian: 0x0100 = [0x00, 0x01], 0x0200 = [0x00, 0x02]
    // Expected averages: (0x0100 + 0x0200)/2 = 0x0180 = [0x80, 0x01]
    //                    (0x0010 + 0x0020)/2 = 0x0018 = [0x18, 0x00]
    val frame0L = Array[Byte](0x00, 0x01) // 256
    val frame0R = Array[Byte](0x00, 0x02) // 512  avg = 384 = 0x0180
    val frame1L = Array[Byte](0x10, 0x00) // 16
    val frame1R = Array[Byte](0x20, 0x00) // 32   avg = 24  = 0x0018
    val stereo  = frame0L ++ frame0R ++ frame1L ++ frame1R
    val meta    = AudioMeta(sampleRate = 16000, numChannels = 2, bitDepth = 16)

    val result = AudioPreprocessing.toMono(stereo, meta)
    assert(result.isRight, s"toMono should succeed: $result")
    val (monoBytes, monoMeta) = result.toOption.get
    assert(monoMeta.numChannels == 1)
    assert(monoBytes.length == 4, s"Expected 4 bytes for 2 mono frames, got ${monoBytes.length}")
    // frame0 avg = 0x0180 -> little-endian [0x80, 0x01]
    assert(monoBytes(0) == 0x80.toByte, f"byte0: got 0x${monoBytes(0).toInt & 0xff}%02x")
    assert(monoBytes(1) == 0x01.toByte, f"byte1: got 0x${monoBytes(1).toInt & 0xff}%02x")
    // frame1 avg = 0x0018 -> little-endian [0x18, 0x00]
    assert(monoBytes(2) == 0x18.toByte, f"byte2: got 0x${monoBytes(2).toInt & 0xff}%02x")
    assert(monoBytes(3) == 0x00.toByte, f"byte3: got 0x${monoBytes(3).toInt & 0xff}%02x")
  }
}
