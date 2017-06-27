package org.janelia.saalfeldlab.hotknife.ops;

import java.util.function.Supplier;

import net.imagej.ops.Op;
import net.imagej.ops.OpService;
import net.imagej.ops.special.computer.Computers;
import net.imagej.ops.special.computer.UnaryComputerOp;
import net.imglib2.RandomAccessible;
import net.imglib2.cache.img.CellLoader;
import net.imglib2.cache.img.SingleCellArrayImg;
import net.imglib2.img.Img;
import net.imglib2.img.array.ArrayImg;
import net.imglib2.type.NativeType;

public class UnaryComputerOpCellLoader<T, S extends NativeType<S>, R extends RandomAccessible<T>> implements CellLoader<S> {

	private final R source;

	private final UnaryComputerOp<R,Img<S>> op;

	public UnaryComputerOpCellLoader(final R source, final UnaryComputerOp<R,Img<S>> op) {

		this.source = source;
		this.op = op;
	}

	@SuppressWarnings("unchecked")
	public UnaryComputerOpCellLoader(
			final R source,
			final Supplier<S> targetTypeSupplier,
			final OpService opService,
			final Class<? extends Op> opClass,
			final Object[] args) {

		this.source = source;

		/*
		 * TODO Ugh!
		 * Instead of passing instances, passing Type would be better, Computers.unary
		 * does not yet support Type though and classes loose their generic parameters by
		 * erasure.
		 */
		final S targetType = targetTypeSupplier.get();
		op = Computers.unary(
				opService,
				opClass,
				(Img<S>)new ArrayImg(null, new long[source.numDimensions()], targetType.getEntitiesPerPixel()),
				source,
				args);
	}

	@Override
	public void load(final SingleCellArrayImg<S, ?> cell) throws Exception {

		op.compute(source, cell);
	}
}