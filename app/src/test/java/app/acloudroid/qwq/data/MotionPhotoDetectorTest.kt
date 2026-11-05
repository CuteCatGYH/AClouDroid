package app.acloudroid.qwq.data

import app.acloudroid.qwq.feature.photos.data.MotionPhotoDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionPhotoDetectorTest {

    @Test
    fun parseXmpString_standardGCameraMotionPhoto_returnsValidOffset() {
        val xmp = """
            <?xpacket begin="﻿" id="W5M0MpCehiHzreSzNTczkc9d"?>
            <x:xmpmeta xmlns:x="adobe:ns:meta/">
             <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
              <rdf:Description rdf:about=""
                xmlns:GCamera="http://ns.google.com/photos/1.0/camera/"
                GCamera:MotionPhoto="1"
                GCamera:MicroVideoOffset="3500000"
                GCamera:MicroVideoPresentationTimestampUs="1500000"/>
             </rdf:RDF>
            </x:xmpmeta>
            <?xpacket end="w"?>
        """.trimIndent()

        val fileSize = 10_000_000L
        val result = MotionPhotoDetector.parseXmpString(xmp, fileSize)

        assertTrue(result.isMotionPhoto)
        assertEquals(6_500_000L, result.videoOffset)
        assertEquals(3_500_000L, result.videoSize)
        assertEquals(1_500_000L, result.presentationTimestampUs)
    }

    @Test
    fun parseXmpString_noMotionPhotoFlag_returnsFalse() {
        val xmp = """
            <x:xmpmeta xmlns:x="adobe:ns:meta/">
             <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
              <rdf:Description rdf:about=""
                xmlns:GCamera="http://ns.google.com/photos/1.0/camera/"
                GCamera:MotionPhoto="0"
                GCamera:MicroVideoOffset="3500000"/>
             </rdf:RDF>
            </x:xmpmeta>
        """.trimIndent()

        val fileSize = 10_000_000L
        val result = MotionPhotoDetector.parseXmpString(xmp, fileSize)

        assertFalse(result.isMotionPhoto)
        assertEquals(0L, result.videoOffset)
        assertEquals(0L, result.videoSize)
    }

    @Test
    fun parseXmpString_containerItemFormat_returnsValidOffset() {
        val xmp = """
            <x:xmpmeta xmlns:x="adobe:ns:meta/">
             <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
              <rdf:Description rdf:about="" xmlns:Container="http://ns.google.com/photos/1.0/container/">
               <Container:Directory>
                <rdf:Seq>
                 <rdf:li><Container:Item Item:Mime="image/jpeg" Item:Semantic="Primary" Item:Length="0"/></rdf:li>
                 <rdf:li><Container:Item Item:Mime="video/mp4" Item:Semantic="MotionPhoto" Item:Length="2500000" Item:Padding="0"/></rdf:li>
                </rdf:Seq>
               </Container:Directory>
              </rdf:Description>
             </rdf:RDF>
            </x:xmpmeta>
        """.trimIndent()

        val fileSize = 10_000_000L
        val result = MotionPhotoDetector.parseXmpString(xmp, fileSize)

        assertTrue(result.isMotionPhoto)
        assertEquals(7_500_000L, result.videoOffset)
        assertEquals(2_500_000L, result.videoSize)
    }

    @Test
    fun parseXmpString_containerItemFormatWithPadding_includesPaddingInVideoSize() {
        val xmp = """
            <x:xmpmeta xmlns:x="adobe:ns:meta/">
             <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
              <rdf:Description rdf:about="" xmlns:Container="http://ns.google.com/photos/1.0/container/">
               <Container:Directory>
                <rdf:Seq>
                 <rdf:li><Container:Item Item:Mime="image/jpeg" Item:Semantic="Primary" Item:Length="0"/></rdf:li>
                 <rdf:li><Container:Item Item:Mime="video/mp4" Item:Semantic="MotionPhoto" Item:Length="2000000" Item:Padding="500000"/></rdf:li>
                </rdf:Seq>
               </Container:Directory>
              </rdf:Description>
             </rdf:RDF>
            </x:xmpmeta>
        """.trimIndent()

        val fileSize = 10_000_000L
        val result = MotionPhotoDetector.parseXmpString(xmp, fileSize)

        assertTrue(result.isMotionPhoto)
        assertEquals(7_500_000L, result.videoOffset)
        assertEquals(2_500_000L, result.videoSize)
    }

    @Test
    fun parseXmpString_offsetExceedsFileSize_returnsFalse() {
        val xmp = """
            <x:xmpmeta xmlns:x="adobe:ns:meta/">
             <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
              <rdf:Description rdf:about=""
                xmlns:GCamera="http://ns.google.com/photos/1.0/camera/"
                GCamera:MotionPhoto="1"
                GCamera:MicroVideoOffset="15000000"/>
             </rdf:RDF>
            </x:xmpmeta>
        """.trimIndent()

        val fileSize = 10_000_000L
        val result = MotionPhotoDetector.parseXmpString(xmp, fileSize)

        assertFalse(result.isMotionPhoto)
    }

    @Test
    fun parseXmpString_samsungMultilineHeic_returnsValidOffset() {
        val xmp = """
            <x:xmpmeta xmlns:x="adobe:ns:meta/" x:xmptk="Adobe XMP Core Test.SNAPSHOT">
              <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
                <rdf:Description rdf:about=""
                    xmlns:hdrgm="http://ns.adobe.com/hdr-gain-map/1.0/"
                    xmlns:Container="http://ns.google.com/photos/1.0/container/"
                    xmlns:Item="http://ns.google.com/photos/1.0/container/item/"
                    xmlns:GCamera="http://ns.google.com/photos/1.0/camera/"
                  hdrgm:Version="1.0"
                  GCamera:MotionPhoto="1"
                  GCamera:MotionPhotoVersion="1"
                  GCamera:MotionPhotoPresentationTimestampUs="1466901">
                  <Container:Directory>
                    <rdf:Seq>
                      <rdf:li rdf:parseType="Resource">
                        <Container:Item
                          Item:Semantic="Primary"
                          Item:Mime="image/heic"
                          Item:Padding="8"/>
                      </rdf:li>
                      <rdf:li rdf:parseType="Resource">
                        <Container:Item
                          Item:Semantic="GainMap"
                          Item:Mime="image/heic"
                          Item:Length="0"/>
                      </rdf:li>
                      <rdf:li rdf:parseType="Resource">
                        <Container:Item
                          Item:Mime="video/mp4"
                          Item:Semantic="MotionPhoto"
                          Item:Length="5096414"
                          Item:Padding="0"/>
                      </rdf:li>
                    </rdf:Seq>
                  </Container:Directory>
                </rdf:Description>
              </rdf:RDF>
            </x:xmpmeta>
        """.trimIndent()

        val fileSize = 5561654L
        val result = MotionPhotoDetector.parseXmpString(xmp, fileSize)

        assertTrue(result.isMotionPhoto)
        assertEquals(465240L, result.videoOffset)
        assertEquals(5096414L, result.videoSize)
    }

    @Test
    fun extractXmpFromBytes_scansHeicDirectXml_returnsXmpString() {
        val xmpPayload = "<x:xmpmeta>hello xmp</x:xmpmeta>"
        val dummyBytes = "some_heic_prefix".toByteArray() + xmpPayload.toByteArray() + "some_heic_suffix".toByteArray()

        val extracted = MotionPhotoDetector.extractXmpFromBytes(dummyBytes)
        assertEquals("<x:xmpmeta>hello xmp</x:xmpmeta>", extracted)
    }
}
