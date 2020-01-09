package org.janelia.saalfeldlab.hotknife;

import java.io.IOException;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;

import org.janelia.saalfeldlab.hotknife.util.Show;
import org.janelia.saalfeldlab.n5.N5FSReader;
import org.janelia.saalfeldlab.n5.imglib2.N5Utils;

import bdv.util.BdvOptions;
import bdv.util.BdvStackSource;
import bdv.util.RandomAccessibleIntervalMipmapSource;
import bdv.util.volatiles.SharedQueue;
import bdv.viewer.Source;
import mpicbg.spim.data.sequence.FinalVoxelDimensions;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import net.imglib2.util.Util;
import picocli.CommandLine.Option;

public class Cost implements Callable<Void>
{
	final FinalVoxelDimensions voxelDimensions = new FinalVoxelDimensions("px", new double[]{1, 1, 1});

	private boolean useVolatile = true;

	@Option(names = {"--i"}, required = false, description = "N5 file with min face, e.g. --i /nrs/flyem/render/n5/Z1217_19m/Sec04/stacks")
	private String rawN5 = "/nrs/flyem/tmp/VNC.n5";

	@Option(names = {"--d"}, required = false, description = "N5 dataset name, e.g. --d /v1_1_affine_filtered_1_26365___20191217_153959")
	private String datasetName = "/zcorr/Sec22___20200106_083252";

	public static final void main(final String... args) throws IOException, InterruptedException, ExecutionException
	{
		//CommandLine.call(new Cost(), args);
		new Cost().call();
	}

	@Override
	public final Void call() throws IOException, InterruptedException, ExecutionException
	{
		final MipMapData data = new MipMapData( new N5FSReader(rawN5), datasetName );

		final RandomAccessibleInterval< UnsignedByteType > mipmap = data.getMipmap( 6 );
		ImageJFunctions.show( mipmap );

		data.display( useVolatile );
		//displayData( data.openVolatileMipmaps(), data.mipmapScales() );

		return null;
	}

	public class MipMapData
	{
		final N5FSReader n5;
		final String datasetName;

		final int numScales;
		final double[][] mipmapScales;
		final long[][] dimensions;
		final AffineTransform3D[] mipmapTransforms;

		final RandomAccessibleInterval<UnsignedByteType>[] volatilemipmaps;
		final RandomAccessibleInterval<UnsignedByteType>[] mipmaps;

		@SuppressWarnings("unchecked")
		public MipMapData( final N5FSReader n5, final String datasetName ) throws IOException
		{
			this.n5 = n5;
			this.datasetName = datasetName;
			this.numScales = n5.list(datasetName).length;

			this.volatilemipmaps = new RandomAccessibleInterval[numScales];
			this.mipmaps = new RandomAccessibleInterval[numScales];

			this.mipmapScales = new double[numScales][];
			this.mipmapTransforms = new AffineTransform3D[numScales];
			this.dimensions = new long[numScales][];

			for (int s = 0; s < numScales; ++s)
			{
				final String datasetNameMipMap = datasetName + "/s" + s;

				final long[] dims = n5.getAttribute(datasetNameMipMap, "dimensions", long[].class);
				final long[] downsamplingFactors = n5.getAttribute(datasetNameMipMap, "downsamplingFactors", long[].class);
				final double[] scale = new double[dims.length];

				if (downsamplingFactors == null)
				{
					final int si = 1 << s;
					for (int i = 0; i < scale.length; ++i)
						scale[i] = si;
				}
				else
				{
					for (int i = 0; i < scale.length; ++i)
						scale[i] = downsamplingFactors[i];
				}

				mipmapScales[s] = scale;
				dimensions[s] = dims;

				final AffineTransform3D mipmapTransform = new AffineTransform3D();
				mipmapTransform.set(
						mipmapScales[ s ][ 0 ], 0, 0, 0.5 * ( mipmapScales[ s ][ 0 ] - 1 ),
						0, mipmapScales[ s ][ 1 ], 0, 0.5 * ( mipmapScales[ s ][ 1 ] - 1 ),
						0, 0, mipmapScales[ s ][ 2 ], 0.5 * ( mipmapScales[ s ][ 2 ] - 1 ) );
				mipmapTransforms[ s ] = mipmapTransform;

				//volatilemipmaps[s] = N5Utils.openVolatile( n5, datasetNameMipMap );
				//mipmaps[s] = N5Utils.open( n5, datasetNameMipMap );

				System.out.println( s + ": " +
						", scale: " + Util.printCoordinates( scale ) +
						", size: " + Util.printCoordinates( dims ) +
						", t=" + mipmapTransform);
			}
		}

		public int numScales() { return numScales; }
		public double[][] mipmapScales(){ return mipmapScales; }
		public long[][] dimensions(){ return dimensions; }
		public AffineTransform3D[] mipmapTransforms(){ return mipmapTransforms; }

		public RandomAccessibleInterval<UnsignedByteType> getMipmap( final int scale ) throws IOException
		{
			if ( mipmaps[scale] == null )
				mipmaps[scale] = N5Utils.open( n5, datasetName + "/s" + scale );

			return mipmaps[scale];
		}

		public RandomAccessibleInterval<UnsignedByteType> getVolatileMipmap( final int scale ) throws IOException
		{
			if ( volatilemipmaps[scale] == null )
				volatilemipmaps[scale] = N5Utils.openVolatile( n5, datasetName + "/s" + scale );

			return volatilemipmaps[scale];
		}

		public RandomAccessibleInterval<UnsignedByteType>[] openVolatileMipmaps() throws IOException
		{
			for (int s = 0; s < numScales; ++s)
				if ( volatilemipmaps[s] == null )
					volatilemipmaps[s] = N5Utils.openVolatile( n5, datasetName + "/s" + s );

			return volatilemipmaps;
		}

		public RandomAccessibleInterval<UnsignedByteType>[] openMipmaps() throws IOException
		{
			for (int s = 0; s < numScales; ++s)
				if ( mipmaps[s] == null )
					mipmaps[s] = N5Utils.open( n5, datasetName + "/s" + s );

			return mipmaps;
		}

		public BdvStackSource<?> display( final boolean useVolatile ) throws IOException
		{
			openVolatileMipmaps();

			final int numProc = Runtime.getRuntime().availableProcessors();
			final SharedQueue queue = new SharedQueue(Math.min(8, Math.max(1, numProc / 2)));

			BdvStackSource<?> bdv = null;

			final RandomAccessibleIntervalMipmapSource<?> mipmapSource =
					new RandomAccessibleIntervalMipmapSource<>(
							volatilemipmaps,
							new UnsignedByteType(),
							mipmapScales,
							voxelDimensions,
							datasetName);

			final Source<?> volatileMipmapSource;

			if (useVolatile)
				volatileMipmapSource = mipmapSource.asVolatile(queue);
			else
				volatileMipmapSource = mipmapSource;

			bdv = Show.mipmapSource(volatileMipmapSource, bdv, BdvOptions.options()./*screenScales(new double[] {0.5}).*/numRenderingThreads(numProc));

			return bdv;
		}
	}
}

