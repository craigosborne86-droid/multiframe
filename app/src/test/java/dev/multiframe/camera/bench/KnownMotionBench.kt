package dev.multiframe.camera.bench

import dev.multiframe.camera.pipeline.*
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Properties
import kotlin.math.floor
import kotlin.math.max

/** Opt-in optical-signal benchmark. Forced SR here is diagnostic, not capture policy. */
class KnownMotionBench {
    @Test fun evaluateFrozenKnownMotion() {
        val root = System.getProperty("mf.burst.dir")?.let(::File)
        assumeTrue(root?.isDirectory == true && File(root, "manifest.json").isFile)
        val inputs = root!!.listFiles()!!.filter { File(it, "synthetic.properties").isFile }.sortedBy { it.name }
        assumeTrue(inputs.isNotEmpty())
        assertTrue(NativeMerge.isAvailable())
        val out = File(requireNotNull(System.getProperty("mf.burst.out"))).apply { mkdirs() }
        for (input in inputs) evaluate(input, File(out, input.name).apply { mkdirs() })
    }

    private fun evaluate(input: File, out: File) {
        val p = Properties().apply { File(input, "synthetic.properties").inputStream().use { load(it) } }
        fun int(k: String) = p.getProperty(k).toInt()
        val w=int("width"); val h=int("height"); val n=int("frames")
        val blackValues=p.getProperty("black").split(',').map(String::toInt)
        val profile=SensorProfile(IntArray(4) { blackValues[it % blackValues.size] },int("white"),
            p.getProperty("cfa").split(',').map(String::toInt).toIntArray())
        val frames=List(n) { i ->
            val bytes=File(input,"frame-%02d.raw16".format(i)).readBytes()
            ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.LITTLE_ENDIAN).apply { put(bytes); rewind() }
        }
        val gains=FloatArray(n) { p.getProperty("frame.$it.gain", "1").toFloat() }
        val sigma=p.getProperty("sigmaDn").toDouble()
        val noise=requireNotNull(RawNoiseModel.fromNormalized(DoubleArray(8) {
            if (it%2==0) 0.0 else sigma*sigma/((profile.whiteLevel-profile.blackLevel[it/2]).toDouble().let { r -> r*r })
        },FloatArray(4) { profile.blackLevel[it].toFloat() },profile.whiteLevel))
        val tx=max(1,w/64); val ty=max(1,h/64); val tiles=tx*ty
        val fields=ArrayList<AlignmentField>()
        val exact=List(n-1) { k ->
            val dx=p.getProperty("frame.${k+1}.dxSensor").toFloat()/2
            val dy=p.getProperty("frame.${k+1}.dySensor").toFloat()/2
            val ix=floor(dx+.5f).toInt(); val iy=floor(dy+.5f).toInt()
            AlignmentField(tx,ty,IntArray(tiles){ix},IntArray(tiles){iy},
                FloatArray(tiles){dx-ix},FloatArray(tiles){dy-iy})
        }
        requireNotNull(NativeMerge.create(w,h,profile)).use { m ->
            val ref=m.lumaProxy(frames[0],w*2)
            for(i in 1 until n) fields.add(requireNotNull(Aligner.alignNative(ref,m.lumaProxy(frames[i],w*2,gains[i]),tx,ty)))
        }
        File(out,"alignment.csv").bufferedWriter().use { csv ->
            csv.appendLine("frame,tile,dxProxy,dyProxy,fracDxProxy,fracDyProxy,trueDxSensor,trueDySensor")
            for(i in fields.indices) for(t in 0 until tiles) {
                val f=fields[i]
                csv.appendLine("${i+1},$t,${f.dx[t]},${f.dy[t]},${f.fracDx[t]},${f.fracDy[t]},${p.getProperty("frame.${i+1}.dxSensor")},${p.getProperty("frame.${i+1}.dySensor")}")
            }
        }
        fun developLinear(raw: ByteBuffer, name: String) {
            val samples=ShortArray(w*h); raw.duplicate().order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(samples)
            val frame=BayerFrame(w,h,samples); val rgb=FloatArray(w*h*3); val pixel=FloatArray(3)
            for(y in 0 until h) for(x in 0 until w) {
                Demosaic.pixel(frame,profile,ColorProfile.NEUTRAL,x,y,pixel)
                pixel.copyInto(rgb,(y*w+x)*3)
            }
            writeFloats(File(out,"$name.rgbf32"),rgb)
            writeBuffer(File(out,"$name.raw16"),raw)
        }
        developLinear(frames[0],"single")
        val summary=StringBuilder("case=${input.name}\nsigmaDn=$sigma\n")
        for(useMetadata in listOf(false,true)) {
            val name=if(useMetadata) "native-metadata" else "native-baseline"
            requireNotNull(NativeMerge.create(w,h,profile)).use { m ->
                m.setReference(frames[0],w*2,if(useMetadata) noise else null)
                for(i in 1 until n) m.addFrame(frames[i],w*2,fields[i-1],gains[i],if(useMetadata) noise else null)
                val result=m.finish(); developLinear(result.first,name)
                summary.appendLine("$name.meanWeight=${result.second.meanContribution}")
                summary.appendLine("$name.sigmaMidDn=${result.second.estimatedSigmaAtMid}")
            }
        }
        val candidates=mutableListOf("sr-estimated" to fields.map { it.withFlatTilesBorrowed() },"sr-known" to exact)
        val refinement=System.getProperty("mf.burst.refine")?.let { File(it,"${input.name}/fields.csv") }
        if (refinement?.isFile == true) {
            val refined=List(n-1) { AlignmentField(tx,ty,IntArray(tiles),IntArray(tiles),FloatArray(tiles),FloatArray(tiles)) }
            for(line in refinement.readLines().drop(1)) {
                val row=line.split(',');val f=refined[row[0].toInt()-1];val tile=row[1].toInt()
                f.dx[tile]=row[2].toInt();f.dy[tile]=row[3].toInt()
                f.fracDx[tile]=row[4].toFloat();f.fracDy[tile]=row[5].toFloat()
            }
            candidates.add("sr-lk" to refined)
        }
        for((name,alignment) in candidates) {
            val prediction=SubPixelCoverage.predict(alignment,profile.cfaPattern)
            summary.appendLine("$name.preGate=${prediction.worthAttempting}")
            summary.appendLine("$name.predictedHoles=${prediction.perChannel.joinToString()}")
            // Force the diagnostic canvas even on a rejected burst, so false
            // accepts/rejects can be scored. Production never calls this bench.
            requireNotNull(NativeSubPixel.create(w,h,profile)).use { sr ->
                assertTrue(sr.setReference(frames[0],w*2))
                for(i in 1 until n) assertTrue(sr.addFrame(frames[i],w*2,alignment[i-1],gains[i]))
                val result=requireNotNull(sr.finish())
                writeBuffer(File(out,"$name.rgb16"),result.rgb16)
                summary.appendLine("$name.actualHoles=${result.interiorHolesPerChannel.joinToString()}")
                summary.appendLine("$name.postGate=${result.acceptable}")
            }
        }
        File(out,"summary.properties").writeText(summary.toString())
        println(summary)
    }

    private fun writeBuffer(file: File, data: ByteBuffer) {
        file.outputStream().channel.use { c ->
            val view=data.duplicate().apply { clear() }; while(view.hasRemaining()) c.write(view)
        }
    }
    private fun writeFloats(file: File, values: FloatArray) {
        val bytes=ByteBuffer.allocate(values.size*4).order(ByteOrder.LITTLE_ENDIAN)
        bytes.asFloatBuffer().put(values); writeBuffer(file,bytes)
    }
}
