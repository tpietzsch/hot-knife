package org.janelia.saalfeldlab.ispim;

import java.awt.event.ActionEvent;
import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.concurrent.Callable;

import javax.swing.ActionMap;
import javax.swing.InputMap;

import org.janelia.saalfeldlab.hotknife.util.Lazy;
import org.janelia.saalfeldlab.ispim.SparkPaiwiseAlignChannelsGeo.N5Data;
import org.janelia.saalfeldlab.ispim.bdv.BDVFlyThrough;
import org.janelia.saalfeldlab.ispim.imglib2.VirtualRasterDataLoader;
import org.janelia.saalfeldlab.n5.N5FSWriter;
import org.janelia.saalfeldlab.n5.N5Writer;
import org.scijava.ui.behaviour.KeyStrokeAdder;
import org.scijava.ui.behaviour.io.InputTriggerConfig;
import org.scijava.ui.behaviour.io.InputTriggerDescription;
import org.scijava.ui.behaviour.util.AbstractNamedAction;

import com.adobe.xmp.impl.Utils;
import com.google.gson.GsonBuilder;

import bdv.util.BdvFunctions;
import bdv.util.BdvOptions;
import bdv.util.BdvStackSource;
import bdv.util.volatiles.SharedQueue;
import bdv.util.volatiles.VolatileViews;
import loci.formats.FormatException;
import net.imglib2.FinalInterval;
import net.imglib2.Interval;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.RealInterval;
import net.imglib2.converter.Converters;
import net.imglib2.img.basictypeaccess.AccessFlags;
import net.imglib2.multithreading.SimpleMultiThreading;
import net.imglib2.realtransform.AffineTransform2D;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.realtransform.Scale3D;
import net.imglib2.type.numeric.ARGBType;
import net.imglib2.type.numeric.integer.UnsignedShortType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.type.volatiles.VolatileUnsignedShortType;
import net.imglib2.util.Intervals;
import net.imglib2.util.Pair;
import net.imglib2.util.Util;
import net.imglib2.util.ValuePair;
import net.imglib2.view.Views;
import net.preibisch.mvrecon.fiji.spimdata.explorer.util.ColorStream;
import net.preibisch.mvrecon.process.fusion.FusionTools;
import net.preibisch.mvrecon.process.fusion.transformed.FusedRandomAccessibleInterval;
import net.preibisch.mvrecon.process.fusion.transformed.TransformView;
import net.preibisch.mvrecon.process.fusion.transformed.TransformWeight;
import net.preibisch.mvrecon.process.fusion.transformed.FusedRandomAccessibleInterval.Fusion;
import picocli.CommandLine;
import picocli.CommandLine.Option;

public class RenderFullStack implements Callable<Void>, Serializable 
{
	private static final long serialVersionUID = 7514909638900822732L;

	@Option(names = "--n5Path", required = true, description = "N5 path, e.g. /nrs/saalfeld/from_mdas/mar24_bis25_s5_r6.n5")
	private String n5Path = null;

	@Option(names = "--channel", required = true, description = "Channel key, e.g. Ch488+561+647nm")
	private String channel = null;

	@Option(names = "--cam", required = true, description = "Cam key, e.g. cam1")
	private String cam = null;

	public static Pair< RandomAccessibleInterval< UnsignedShortType >, N5Data > loadStack(
			final String n5Path,
			final String id,
			final String channel,
			final String cam ) throws IOException, FormatException
	{
		final N5Data n5data = SparkPaiwiseAlignChannelsGeo.openN5( n5Path, id );

		// they stay constant as we keep loading the same stack
		final Interval inputBounds = ViewISPIMStack.openStackSize( n5data.stacks.get( channel ).get( cam ), 0 );

		final RealInterval realBounds2d = ViewISPIMStack.estimateStackBounds(
				inputBounds,
				n5data.stacks.get( channel ).get( cam ),
				n5data.camTransforms.get( channel ).get( cam ).inverse(),
				n5data.alignments.get( channel ),
				0,
				n5data.lastSliceIndex,
				true );

		final Interval bounds2d = Intervals.smallestContainingInterval( realBounds2d );

		final long[] min = new long[] { bounds2d.min( 0 ), bounds2d.min( 1 ), 0 };
		final long[] max = new long[] { bounds2d.max( 0 ), bounds2d.max( 1 ), n5data.lastSliceIndex };

		final Interval interval = new FinalInterval( min, max );

		System.out.println( new Date(System.currentTimeMillis() ) + ": " + id + "," + channel + "," + cam + ": interval=" + interval );
		System.out.println( new Date(System.currentTimeMillis() ) + ": " + id + "," + channel + "," + cam + ": affine3d=" + n5data.affine3D.get( channel ) );

		final VirtualRasterDataLoader< UnsignedShortType > loader =
				new VirtualRasterDataLoader<UnsignedShortType>(
						inputBounds,
						n5data.stacks.get( channel ).get( cam ),
						n5data.alignments.get( channel ),
						0,
						n5data.lastSliceIndex,
						n5data.camTransforms.get( channel ).get( cam ),
						min,
						new UnsignedShortType() );

		final int blockSizeXY = (int)Math.min( interval.dimension( 0 ), interval.dimension( 1 ) );
		final int[] blockSize = new int[] { blockSizeXY, blockSizeXY, 10 };
		System.out.println( new Date(System.currentTimeMillis() ) + ": " + id + "," + channel + "," + cam + ": blocksize=" + Util.printCoordinates( blockSize ) );

		final RandomAccessibleInterval<UnsignedShortType> cachedImg =
				Views.translate(
						Lazy.process(
								interval,
								blockSize,
								new UnsignedShortType(),
								AccessFlags.setOf( AccessFlags.VOLATILE ),
								loader ),
						min );

		//final RandomAccessibleInterval< VolatileUnsignedShortType > volatileRA =
		//		VolatileViews.wrapAsVolatile( cachedImg, queue );

		return new ValuePair<>( cachedImg, n5data );
	}

	protected static void testRendering( final String n5Path, final String id, final String channel, final String cam ) throws IOException, FormatException
	{
		final int numFetchThreads = Runtime.getRuntime().availableProcessors() / 2;
		System.out.println("building SharedQueue with " + numFetchThreads + " FetcherThreads" );
		final SharedQueue queue = new SharedQueue(numFetchThreads, 1 );

		RandomAccessibleInterval< UnsignedShortType > stack = loadStack( n5Path, id, channel, cam ).getA();

		BdvOptions options = BdvOptions.options().numRenderingThreads( numFetchThreads );

		RandomAccessibleInterval< VolatileUnsignedShortType > vstack = VolatileViews.wrapAsVolatile( stack, queue );
		BdvStackSource<?> bdv = BdvFunctions.show( Views.extendValue( vstack, 0 ), new FinalInterval( stack ), id + "," + channel + "," + cam, options );
		bdv.setDisplayRange( 0, 1000 );
		bdv.setColor( new ARGBType( ARGBType.rgba(0, 255, 0, 0)));

		final N5Data n5data = SparkPaiwiseAlignChannelsGeo.openN5( n5Path, id );
		bdv = SparkPaiwiseAlignChannelsGeo.displayCam( bdv, channel, cam, n5data.stacks.get( channel ).get( cam ), n5data.alignments.get( channel ), n5data.camTransforms.get( channel ).get( cam ), new AffineTransform3D(), 0, n5data.lastSliceIndex );
		bdv.setDisplayRange( 0, 1000 );
		bdv.setColor( new ARGBType( ARGBType.rgba(255, 0, 255, 0)));
	}

	protected static void testTwoStacks(
			final String n5Path,
			final String idA, final String channelA, final String camA,
			final String idB, final String channelB, final String camB ) throws IOException, FormatException
	{
		final int numFetchThreads = Runtime.getRuntime().availableProcessors() / 2;
		System.out.println("building SharedQueue with " + numFetchThreads + " FetcherThreads" );
		final SharedQueue queue = new SharedQueue(numFetchThreads, 1 );

		Pair< RandomAccessibleInterval< UnsignedShortType >, N5Data > stackA = loadStack( n5Path, idA, channelA, camA );
		Pair< RandomAccessibleInterval< UnsignedShortType >, N5Data > stackB = loadStack( n5Path, idB, channelB, camB );

		System.out.println( "Loaded all stacks." );

		final Scale3D anisotropy = new Scale3D(0.2, 0.2, 0.85);

		BdvOptions options;
		BdvStackSource<?> bdv;

		RandomAccessibleInterval< VolatileUnsignedShortType > vstackA = VolatileViews.wrapAsVolatile( stackA.getA(), queue );
		options = BdvOptions.options().sourceTransform( stackA.getB().affine3D.get( channelA ).preConcatenate( anisotropy ) ).numRenderingThreads( numFetchThreads );
		bdv = BdvFunctions.show( Views.extendValue( vstackA, 0 ), new FinalInterval( stackA.getA() ), idA + "," + channelA + "," + camA, options );
		bdv.setDisplayRange( 0, 1000 );
		bdv.setColor( new ARGBType( ARGBType.rgba(0, 255, 0, 0)));

		RandomAccessibleInterval< VolatileUnsignedShortType > vstackB = VolatileViews.wrapAsVolatile( stackB.getA(), queue );
		options = BdvOptions.options().sourceTransform( stackB.getB().affine3D.get( channelB ).preConcatenate( anisotropy ) ).numRenderingThreads( numFetchThreads ).addTo( bdv );
		bdv = BdvFunctions.show( Views.extendValue( vstackB, 0 ), new FinalInterval( stackB.getA() ), idB + "," + channelB + "," + camB, options );
		bdv.setDisplayRange( 0, 1000 );
		bdv.setColor( new ARGBType( ARGBType.rgba(255, 0, 255, 0)));

		ViewISPIMStacksN5.setupRecordMovie( bdv );
	}

	public static RandomAccessibleInterval< UnsignedShortType > fuseMax(final String n5Path, final List<String> ids, final String channel, final String cam ) throws IOException, FormatException
	{
		final ArrayList< Pair< RandomAccessibleInterval< UnsignedShortType >, AffineTransform3D > > toFuse = new ArrayList<>();

		//final Scale3D anisotropy = new Scale3D(1, 1, 0.85/0.2);

		for ( final String id : ids )
		{
			Pair< RandomAccessibleInterval< UnsignedShortType >, N5Data > stack = loadStack( n5Path, id, channel, cam );
			AffineTransform3D transformA = stack.getB().affine3D.get( channel );//.preConcatenate( anisotropy );
			toFuse.add( new ValuePair<>( stack.getA(), transformA ) );
		}

		final long[] min = new long[] { Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE };
		final long[] max = new long[] { -Long.MAX_VALUE, -Long.MAX_VALUE, -Long.MAX_VALUE };

		for ( final Pair< RandomAccessibleInterval< UnsignedShortType >, AffineTransform3D > data : toFuse )
		{
			Interval interval = Intervals.smallestContainingInterval( data.getB().estimateBounds( data.getA() ) );
			
			for ( int d = 0; d < interval.numDimensions(); ++d )
			{
				min[ d ] = Math.min( min[ d ], interval.min( d ) );
				max[ d ] = Math.max( max[ d ], interval.max( d ) );
			}
		}

		final Interval bb = new FinalInterval(min, max);

		final ArrayList< RandomAccessibleInterval< FloatType > > images = new ArrayList<>();

		for ( final Pair< RandomAccessibleInterval< UnsignedShortType >, AffineTransform3D > data : toFuse )
			images.add( TransformView.transformView( data.getA(), data.getB(), bb, 0, 1 ) );

		FusedRandomAccessibleInterval f = new FusedRandomAccessibleInterval( FusionTools.getFusedZeroMinInterval( bb ), images, null );
		f.setFusion( Fusion.MAX );

		RandomAccessibleInterval< UnsignedShortType > output = Views.translate( Converters.convert( f, (i,o) -> { o.set( Math.round( i.get() ) );}, new UnsignedShortType() ), min );

		return output;
		/*
		BdvOptions options = BdvOptions.options().numRenderingThreads( 8 );
		BdvStackSource< ? > bdv = null;

		bdv = BdvFunctions.show( Views.extendValue( output, 0 ), new FinalInterval( output ), "max", options );
		bdv.setDisplayRange( 0, 500 );
		*/
	}

	@Override
	public Void call() throws Exception {

		final N5Writer n5 = new N5FSWriter(
				n5Path,
				new GsonBuilder().
					registerTypeAdapter(
						AffineTransform3D.class,
						new AffineTransform3DAdapter()).
					registerTypeAdapter(
						AffineTransform2D.class,
						new AffineTransform2DAdapter()));

		final List<String> allIds = SparkPaiwiseAlignChannelsGeoAll.getIds(n5);
		Collections.sort( allIds );

		SimpleMultiThreading.threadHaltUnClean();

		final int numFetchThreads = Runtime.getRuntime().availableProcessors() / 2;
		System.out.println( new Date(System.currentTimeMillis() ) + ": building SharedQueue with " + numFetchThreads + " FetcherThreads" );
		final SharedQueue queue = new SharedQueue(numFetchThreads, 1 );

		//VolatileViews.wrapAsVolatile( cachedImg, queue );

		final ArrayList< Pair< RandomAccessibleInterval< UnsignedShortType >, N5Data > > stacks = new ArrayList<>();

		for ( final String id : allIds )
			stacks.add( loadStack( n5Path, id, channel, cam ) );

		System.out.println( new Date(System.currentTimeMillis() ) + ": Loaded all stacks." );

		final Scale3D anisotropy = new Scale3D(0.2, 0.2, 0.85);

		BdvStackSource<?> bdv = null;

		for ( final Pair< RandomAccessibleInterval< UnsignedShortType >, N5Data > stack : stacks )
		{
			BdvOptions options = BdvOptions.options().sourceTransform( stack.getB().affine3D.get( channel ).preConcatenate( anisotropy ) ).numRenderingThreads( numFetchThreads ).addTo( bdv );

			RandomAccessibleInterval< VolatileUnsignedShortType > vstack = VolatileViews.wrapAsVolatile( stack.getA() );
			bdv = BdvFunctions.show( Views.extendValue( vstack, 0 ), new FinalInterval( stack.getA() ), stack.getB().id + "," + channel + "," + cam, options );
			bdv.setDisplayRange( 0, 1000 );
			bdv.setColor( new ARGBType( ColorStream.next() ) );
		}

		System.out.println( new Date(System.currentTimeMillis() ) + ": Displayed all stacks." );

		ViewISPIMStacksN5.setupRecordMovie( bdv );

		SimpleMultiThreading.threadHaltUnClean();
		return null;
	}

	public static final void main(final String... args) {

		System.out.println(Arrays.toString(args));

		System.exit(new CommandLine(new RenderFullStack()).execute(args));
	}

}