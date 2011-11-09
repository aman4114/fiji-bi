package process;

import ij.CompositeImage;
import ij.IJ;
import ij.ImagePlus;
import ij.gui.PointRoi;
import ij.gui.Roi;
import ij.measure.Calibration;

import java.util.ArrayList;
import java.util.List;
import java.util.Vector;
import java.util.concurrent.atomic.AtomicInteger;

import mpicbg.imglib.algorithm.scalespace.DifferenceOfGaussian.SpecialPoint;
import mpicbg.imglib.algorithm.scalespace.DifferenceOfGaussianPeak;
import mpicbg.imglib.image.Image;
import mpicbg.imglib.image.display.imagej.ImageJFunctions;
import mpicbg.imglib.multithreading.SimpleMultiThreading;
import mpicbg.imglib.outofbounds.OutOfBoundsStrategyMirrorFactory;
import mpicbg.imglib.type.numeric.integer.UnsignedByteType;
import mpicbg.imglib.type.numeric.integer.UnsignedShortType;
import mpicbg.imglib.type.numeric.real.FloatType;
import mpicbg.imglib.util.Util;
import mpicbg.models.AbstractAffineModel3D;
import mpicbg.models.IllDefinedDataPointsException;
import mpicbg.models.InvertibleBoundable;
import mpicbg.models.Model;
import mpicbg.models.NotEnoughDataPointsException;
import mpicbg.models.Point;
import mpicbg.models.PointMatch;
import mpicbg.models.Tile;
import mpicbg.models.TileConfiguration;
import mpicbg.pointdescriptor.AbstractPointDescriptor;
import mpicbg.pointdescriptor.ModelPointDescriptor;
import mpicbg.pointdescriptor.SimplePointDescriptor;
import mpicbg.pointdescriptor.exception.NoSuitablePointsException;
import mpicbg.pointdescriptor.matcher.Matcher;
import mpicbg.pointdescriptor.matcher.SubsetMatcher;
import mpicbg.pointdescriptor.model.TranslationInvariantModel;
import mpicbg.pointdescriptor.model.TranslationInvariantRigidModel2D;
import mpicbg.pointdescriptor.model.TranslationInvariantRigidModel3D;
import mpicbg.pointdescriptor.similarity.SimilarityMeasure;
import mpicbg.pointdescriptor.similarity.SquareDistance;
import mpicbg.spim.io.IOFunctions;
import mpicbg.spim.mpicbg.PointMatchGeneric;
import mpicbg.spim.mpicbg.TileConfigurationSPIM;
import mpicbg.spim.registration.ViewDataBeads;
import mpicbg.spim.registration.ViewStructure;
import mpicbg.spim.registration.bead.BeadRegistration;
import mpicbg.spim.registration.bead.error.GlobalErrorStatistics;
import mpicbg.spim.registration.detection.AbstractDetection;
import mpicbg.spim.registration.detection.DetectionSegmentation;
import mpicbg.spim.segmentation.InteractiveDoG;
import plugin.DescriptorParameters;
import plugin.Descriptor_based_registration;
import plugin.Descriptor_based_series_registration;
import fiji.util.KDTree;
import fiji.util.NNearestNeighborSearch;

public class Matching 
{
	public static void descriptorBasedRegistration( final ImagePlus imp1, final ImagePlus imp2, final DescriptorParameters params )
	{
		Model<?> model1;
		Model<?> model2;
		
		// zStretching if applicable
		float zStretching1 = params.dimensionality == 3 ? (float)imp1.getCalibration().pixelDepth / (float)imp1.getCalibration().pixelWidth : 1;
		float zStretching2 = params.dimensionality == 3 ? (float)imp2.getCalibration().pixelDepth / (float)imp2.getCalibration().pixelWidth : 1;

		if ( !params.reApply )
		{
			// get the peaks
			ArrayList<DifferenceOfGaussianPeak<FloatType>> peaks1 = extractCandidates( imp1, params.channel1, 0, params );
			ArrayList<DifferenceOfGaussianPeak<FloatType>> peaks2 = extractCandidates( imp2, params.channel2, 0, params );
	
			// filter for ROI
			peaks1 = filterForROI( params.roi1, peaks1 );
			peaks2 = filterForROI( params.roi2, peaks2 );
			
			// compute ransac
			ArrayList<PointMatch> finalInliers = new ArrayList<PointMatch>();
			model1 = pairwiseMatching( finalInliers, peaks1, peaks2, zStretching1, zStretching2, params, "" );				
			model2 = params.model.copy();
			IJ.log( "" + model1 );
			
			// nothing found
			if ( model1 == null || model2 == null )
				return;
			
			// set the static model
			Descriptor_based_registration.lastModel1 = (InvertibleBoundable)model1.copy();
			Descriptor_based_registration.lastModel2 = (InvertibleBoundable)model2.copy();
			Descriptor_based_registration.lastDimensionality = params.dimensionality;
			
			// set point rois if 2d and wanted
			if ( params.setPointsRois )
				setPointRois( imp1, imp2, finalInliers );
		}
		else
		{
			model1 = ((Model)Descriptor_based_registration.lastModel1).copy();
			model2 = ((Model)Descriptor_based_registration.lastModel2).copy();
		}
		
		// fuse if wanted
		if ( params.fuse )
		{
			final CompositeImage composite;
			
			if ( params.dimensionality == 3 )
			{
				//IJ.log( "model1: " + model1 );
				//IJ.log( "model2: " + model2 );
				BeadRegistration.concatenateAxialScaling( (AbstractAffineModel3D<?>)model1, imp1.getCalibration().pixelDepth / imp1.getCalibration().pixelWidth );				
				BeadRegistration.concatenateAxialScaling( (AbstractAffineModel3D<?>)model2, imp2.getCalibration().pixelDepth / imp2.getCalibration().pixelWidth );
				//IJ.log( "model1: " + model1 );
				//IJ.log( "model2: " + model2 );
			}
			
			if ( imp1.getType() == ImagePlus.GRAY32 || imp2.getType() == ImagePlus.GRAY32 )
				composite = OverlayFusion.createOverlay( new FloatType(), imp1, imp2, (InvertibleBoundable)model1, (InvertibleBoundable)model2, params.dimensionality );
			else if ( imp1.getType() == ImagePlus.GRAY16 || imp2.getType() == ImagePlus.GRAY16 )
				composite = OverlayFusion.createOverlay( new UnsignedShortType(), imp1, imp2, (InvertibleBoundable)model1, (InvertibleBoundable)model2, params.dimensionality );
			else
				composite = OverlayFusion.createOverlay( new UnsignedByteType(), imp1, imp2, (InvertibleBoundable)model1, (InvertibleBoundable)model2, params.dimensionality );
			
			composite.show();
		}
	}
	
	public static void descriptorBasedStackRegistration( final ImagePlus imp, final DescriptorParameters params )
	{
		final ArrayList<InvertibleBoundable> models;
		
		final int numImages = imp.getNFrames();
		
		// zStretching if applicable
		final float zStretching = params.dimensionality == 3 ? (float)imp.getCalibration().pixelDepth / (float)imp.getCalibration().pixelWidth : 1;
		
		if ( !params.reApply )
		{
			// get the peaks
			final ArrayList<ArrayList<DifferenceOfGaussianPeak<FloatType>>> peaksComplete = new ArrayList<ArrayList<DifferenceOfGaussianPeak<FloatType>>>();
			
			for ( int t = 0; t < numImages; ++t )
				peaksComplete.add( extractCandidates( imp, params.channel1, t, params ) );

			// filter for roi
			final ArrayList<ArrayList<DifferenceOfGaussianPeak<FloatType>>> peaks = new ArrayList<ArrayList<DifferenceOfGaussianPeak<FloatType>>>();
			for ( int t = 0; t < numImages; ++t )
				peaks.add( filterForROI( params.roi1, peaksComplete.get( t ) ) );
			
			// compute descriptormatching between all pairs of images
			final Vector<ComparePair> pairs = descriptorMatching( peaks, numImages, params, zStretching );
	        
	        // perform global optimization
	        models = globalOptimization( pairs, numImages, params );
	        
			// we are done if no roi was selected, otherwise we have to update the roi with the new transformations
			if ( params.roi1 != null )
			{
				int numMatches = countMatches( pairs );
				IJ.log( "\nNumber of matches " + numMatches );
				
				// iterate until it converges
				for ( int iteration = 0; iteration < 5; ++iteration )
				{
					IJ.log( "\nIteration " + (iteration+1) );
				
					final int numMatches2 = performIteration( models, peaksComplete, numImages, params, zStretching );

					IJ.log( "\nNumber of matches " + numMatches2 );

					if ( numMatches == numMatches2 )
						break;
					
					numMatches = numMatches2;
				}
				
			}
	
			// set the static model
			Descriptor_based_series_registration.lastModels = new ArrayList<InvertibleBoundable>();
			Descriptor_based_series_registration.lastModels.addAll( models );
			Descriptor_based_series_registration.lastDimensionality = params.dimensionality;
		}
		else
		{
			models = new ArrayList<InvertibleBoundable>();
			models.addAll( Descriptor_based_series_registration.lastModels );
		}
		
		// fuse
		if ( params.dimensionality == 3 )
			for ( final InvertibleBoundable model : models )
				BeadRegistration.concatenateAxialScaling( (AbstractAffineModel3D<?>)model, imp.getCalibration().pixelDepth / imp.getCalibration().pixelWidth );
		
		final ImagePlus result;
		
		if ( imp.getType() == ImagePlus.GRAY32 )
			result = OverlayFusion.createReRegisteredSeries( new FloatType(), imp, models, params.dimensionality );
		else if ( imp.getType() == ImagePlus.GRAY16 )
			result = OverlayFusion.createReRegisteredSeries( new UnsignedShortType(), imp, models, params.dimensionality );
		else
			result = OverlayFusion.createReRegisteredSeries( new UnsignedByteType(), imp, models, params.dimensionality );
		
		result.show();
	}
	
	/**
	 * Computes one iteration and updates the lastModels ArrayList with the new models
	 * 
	 * @param lastModels - models from last iteration (or init)
	 * @param peaksComplete - all peaks for all images
	 * @param numImages - how many images are there
	 * @param params - the parameters
	 * @param zStretching - the zStretching if applicable
	 * 
	 * @return the number of matches found
	 */
	protected static int performIteration( final ArrayList<InvertibleBoundable> lastModels, final ArrayList<ArrayList<DifferenceOfGaussianPeak<FloatType>>> peaksComplete, 
			final int numImages, final DescriptorParameters params, final float zStretching )
	{
		// filter for roi with updated global coordinates
		final ArrayList<ArrayList<DifferenceOfGaussianPeak<FloatType>>> peaks = new ArrayList<ArrayList<DifferenceOfGaussianPeak<FloatType>>>();
		for ( int t = 0; t < numImages; ++t )
			peaks.add( filterForROI( params.roi1, peaksComplete.get( t ), (Model)lastModels.get( t ) ) );

		// compute descriptormatching between all pairs of images
		final Vector<ComparePair> pairs = descriptorMatching( peaks, numImages, params, zStretching );
    	
        // perform global optimization
		final ArrayList<InvertibleBoundable> models = globalOptimization( pairs, numImages, params );

		// update old models
		lastModels.clear();
		for ( final InvertibleBoundable model : models )
			lastModels.add( model );
	
		// count matches
		return countMatches( pairs );
	}
	
	protected static int countMatches( final List<ComparePair> pairs )
	{
		int numMatches = 0;
		
		for ( final ComparePair pair : pairs )
			numMatches += pair.inliers.size();
		
		return numMatches;
	}
	
	public static Vector<ComparePair> descriptorMatching( final ArrayList<ArrayList<DifferenceOfGaussianPeak<FloatType>>> peaks, final int numImages, final DescriptorParameters params, final float zStretching )
	{
		// get all compare pairs
		final Vector<ComparePair> pairs = getComparePairs( params, numImages );

		// compute all matchings
		final AtomicInteger ai = new AtomicInteger(0);					
        final Thread[] threads = SimpleMultiThreading.newThreads();
        final int numThreads = threads.length;
    	
        for ( int ithread = 0; ithread < threads.length; ++ithread )
            threads[ ithread ] = new Thread(new Runnable()
            {
                public void run()
                {		
                   	final int myNumber = ai.getAndIncrement();
                    
                    for ( int i = 0; i < pairs.size(); i++ )
                    	if ( i%numThreads == myNumber )
                    	{
                    		final ComparePair pair = pairs.get( i );
                    		pair.model = pairwiseMatching( pair.inliers, peaks.get( pair.indexA ), peaks.get( pair.indexB ), zStretching, zStretching, params, pair.indexA + "<->" + pair.indexB );
                    		                    		
                    		if ( pair.model == null )
                    		{
                    			pair.inliers.clear();
                    			pair.model = params.model.copy();
                    		}
                    	}
                }
            });
        
        SimpleMultiThreading.startAndJoin( threads );
        
		return pairs;
	}
	
	public static ArrayList<InvertibleBoundable> globalOptimization( final Vector<ComparePair> pairs, final int numImages, final DescriptorParameters params )
	{
        // perform global optimization
    	final ArrayList<Tile<?>> tiles = new ArrayList<Tile<?>>();
		for ( int t = 0; t < numImages; ++t )
			tiles.add( new Tile( params.model.copy() ) );
		
		// reset the coordinates of all points so that we directly get the correct model
		for ( final ComparePair pair : pairs )
		{
			if ( pair.inliers.size() > 0 )
			{
    			for ( final PointMatch pm : pair.inliers )
				{
					((Particle)pm.getP1()).restoreCoordinates();
					((Particle)pm.getP2()).restoreCoordinates();
				}    			
			}
			//IJ.log( pair.indexA + "<->" + pair.indexB + ": " + pair.model );
		}
		
		for ( final ComparePair pair : pairs )
			addPointMatches( pair.inliers, tiles.get( pair.indexA ), tiles.get( pair.indexB ) );
		
		final TileConfiguration tc = new TileConfiguration();

		boolean fixed = false;
		for ( int t = 0; t < numImages; ++t )
		{
			final Tile<?> tile = tiles.get( t );
			
			if ( tile.getConnectedTiles().size() > 0 )
			{
				tc.addTile( tile );
				if ( !fixed )
				{
					tc.fixTile( tile );
					fixed = true;
				}
			}
			else 
			{
				IJ.log( "Tile " + t + " is not connected to any other tile, cannot compute a model" );
			}
		}
		
		try
		{
			tc.optimize( 10, 10000, 200 );
		}
		catch ( Exception e )
		{
			IJ.log( "Global optimization failed: " + e );
			return null;
		}
		
		// assemble final list of models
		final ArrayList<InvertibleBoundable> models = new ArrayList<InvertibleBoundable>();
		
		for ( int t = 0; t < numImages; ++t )
		{
			final Tile<?> tile = tiles.get( t );
			
			if ( tile.getConnectedTiles().size() > 0 )
			{
				IJ.log( "Tile " + t + " (connected): " + tile.getModel()  );
				models.add( (InvertibleBoundable)tile.getModel() );
			}
			else
			{
				IJ.log( "Tile " + t + " (NOT connected): " + tile.getModel()  );
				models.add( (InvertibleBoundable)params.model.copy() );
			}
		}
		
		return models;
	}
	
	public synchronized static void addPointMatches( final ArrayList<PointMatch> correspondences, final Tile<?> tileA, final Tile<?> tileB )
	{
		if ( correspondences.size() > 0 )
		{
			tileA.addMatches( correspondences );							
			tileB.addMatches( PointMatch.flip( correspondences ) );
			tileA.addConnectedTile( tileB );
			tileB.addConnectedTile( tileA );
		}
	}  

	protected static Vector<ComparePair> getComparePairs( final DescriptorParameters params, final int numImages )
	{
		final Vector<ComparePair> pairs = new Vector<ComparePair>();
		
		if ( params.globalOpt == 0 ) //all-to-all
		{
			for ( int indexA = 0; indexA < numImages - 1; indexA++ )
	    		for ( int indexB = indexA + 1; indexB < numImages; indexB++ )
	    			pairs.add( new ComparePair( indexA, indexB, params.model ) );
		}
		else if ( params.globalOpt == 1 ) //all-to-all-withrange
		{
			for ( int indexA = 0; indexA < numImages - 1; indexA++ )
	    		for ( int indexB = indexA + 1; indexB < numImages; indexB++ )
	    			if ( Math.abs( indexB - indexA ) <= params.range )
	    				pairs.add( new ComparePair( indexA, indexB, params.model ) );			
		}
		else if ( params.globalOpt == 2 ) //all-to-1
		{
			for ( int indexA = 1; indexA < numImages; ++indexA )
				pairs.add( new ComparePair( indexA, 0, params.model ) );
		}
		else // Consecutive
		{
			for ( int indexA = 1; indexA < numImages; ++indexA )
				pairs.add( new ComparePair( indexA, indexA - 1, params.model ) );			
		}
		
		return pairs;
	}
	
	protected static Model<?> pairwiseMatching( final ArrayList<PointMatch> finalInliers, final ArrayList<DifferenceOfGaussianPeak<FloatType>> peaks1, final ArrayList<DifferenceOfGaussianPeak<FloatType>>peaks2, 
			final float zStretching1, final float zStretching2, final DescriptorParameters params, String explanation )
	{
		final Matcher matcher = new SubsetMatcher( params.numNeighbors, params.numNeighbors + params.redundancy );
		ArrayList<PointMatch> candidates = getCorrespondenceCandidates( params.significance, matcher, peaks1, peaks2, null, params.dimensionality, zStretching1, zStretching2 );
		
		// compute ransac
		//ArrayList<PointMatch> finalInliers = new ArrayList<PointMatch>();
		Model<?> finalModel = params.model.copy();
		
		//IJ.log( "after Candidates:" );
		//for ( final PointMatch pm : candidates )
		//{
			//0; (336.40515, 285.3888, 51.396233) [(335.1637, 293.7457, 44.46349)] {(336.40515, 285.3888, 16.24121)} <-> 467; (334.7591, 293.14224, 44.127663) [(335.1637, 293.7457, 44.46349)
		//	if ( ((Particle)pm.getP1()).getID() == 0 )
		//		IJ.log( ((Particle)pm.getP1()).getID() + "; " + Util.printCoordinates( ((Particle)pm.getP1()).getL() ) + " ["+Util.printCoordinates( ((Particle)pm.getP1()).getW() )+"] {" + Util.printCoordinates( ((Particle)pm.getP1()).getPeak().getSubPixelPosition() )+"} <-> " + 
		//				((Particle)pm.getP2()).getID() + "; " + Util.printCoordinates( ((Particle)pm.getP2()).getL() )  + " ["+Util.printCoordinates( ((Particle)pm.getP2()).getW() )+"] {" + Util.printCoordinates( ((Particle)pm.getP2()).getPeak().getSubPixelPosition() )+"}" );
		//}

		IJ.log( "" );
		
		String statement = computeRANSAC( candidates, finalInliers, finalModel, (float)params.ransacThreshold );
		//IJ.log( explanation + ": " + statement );
		
		IJ.log( "Z1 " + zStretching1 );
		IJ.log( "Z2 " + zStretching2 );

		//IJ.log( "after RANSAC:" );
		//for ( final PointMatch pm : finalInliers )
		//{
		//	IJ.log( ((Particle)pm.getP1()).getID() + "; " + Util.printCoordinates( ((Particle)pm.getP1()).getL() ) + " ["+Util.printCoordinates( ((Particle)pm.getP1()).getW() )+"] {" + Util.printCoordinates( ((Particle)pm.getP1()).getPeak().getSubPixelPosition() )+"} <-> " + 
		//			((Particle)pm.getP2()).getID() + "; " + Util.printCoordinates( ((Particle)pm.getP2()).getL() )  + " ["+Util.printCoordinates( ((Particle)pm.getP2()).getW() )+"] {" + Util.printCoordinates( ((Particle)pm.getP2()).getPeak().getSubPixelPosition() )+"}" );
			
		//	pm.getP1().apply( finalModel );
		//	IJ.log( ((Particle)pm.getP1()).getID() + "; " + Util.printCoordinates( ((Particle)pm.getP1()).getL() ) + " ["+Util.printCoordinates( ((Particle)pm.getP1()).getW() )+"] {" + Util.printCoordinates( ((Particle)pm.getP1()).getPeak().getSubPixelPosition() )+"} <-> " + 
		//			((Particle)pm.getP2()).getID() + "; " + Util.printCoordinates( ((Particle)pm.getP2()).getL() )  + " ["+Util.printCoordinates( ((Particle)pm.getP2()).getW() )+"] {" + Util.printCoordinates( ((Particle)pm.getP2()).getPeak().getSubPixelPosition() )+"}" );
		//}
		
		// apply rotation-variant matching after applying the model until it converges
		if ( finalInliers.size() > finalModel.getMinNumMatches() * DescriptorParameters.minInlierFactor )
		{
			int previousNumInliers = 0;
			int numInliers = 0;
			do
			{
				// get the correspondence candidates with the knowledge of the previous model
				candidates = getCorrespondenceCandidates( params.significance, matcher, peaks1, peaks2, finalModel, params.dimensionality, zStretching1, zStretching2 );
				
				// before we compute the RANSAC we will reset the coordinates of all points so that we directly get the correct model
				for ( final PointMatch pm : candidates )
				{
					((Particle)pm.getP1()).restoreCoordinates();
					((Particle)pm.getP2()).restoreCoordinates();
				}			
				
				// compute ransac
				previousNumInliers = finalInliers.size();
				
				final ArrayList<PointMatch> inliers = new ArrayList<PointMatch>();
				Model<?> model2 = params.model.copy();
				statement = computeRANSAC( candidates, inliers, model2, (float)params.ransacThreshold );
				
				numInliers = inliers.size();
				//IJ.log( explanation + ": " + statement );
				
				// update model if this one was better
				if ( numInliers > previousNumInliers )
				{
					finalModel = model2;
					finalInliers.clear();
					finalInliers.addAll( inliers );
					//finalInliers = inliers;
				}
			} 
			while ( numInliers > previousNumInliers );
		}
		else
		{
			IJ.log( explanation + ": " + statement + " - No inliers foundTipp: You could increase the number of neighbors, redundancy or use a model that has more degrees of freedom." );
			finalInliers.clear();
			return null;
		}
		
		IJ.log( explanation + ": " + statement );
		
		return finalModel;
	}

	protected static ArrayList<DifferenceOfGaussianPeak<FloatType>> extractCandidates( final ImagePlus imp, final int channel, final int timepoint, final DescriptorParameters params )
	{
		// get the input images for registration
		final Image<FloatType> img = InteractiveDoG.convertToFloat( imp, channel, timepoint );
		
		// extract Calibrations
		final Calibration cal = imp.getCalibration();
		
		if ( params.dimensionality == 2 )
			img.setCalibration( new float[]{ (float)cal.pixelWidth, (float)cal.pixelHeight } );
		else
			img.setCalibration( new float[]{ (float)cal.pixelWidth, (float)cal.pixelHeight, (float)cal.pixelDepth } );
		
		// extract candidates
		final ArrayList<DifferenceOfGaussianPeak<FloatType>> peaks = computeDoG( img, (float)params.sigma1, (float)params.sigma2, params.lookForMaxima, params.lookForMinima, (float)params.threshold );
		
		// remove invalid peaks
		final int[] stats1 = removeInvalidAndCollectStatistics( peaks );
				
		IJ.log( "Found " + peaks.size() + " candidates for " + imp.getTitle() + " [" + timepoint + "] (" + stats1[ 1 ] + " maxima, " + stats1[ 0 ] + " minima)" );
		
		return peaks;
	}
	
	protected static ArrayList<DifferenceOfGaussianPeak<FloatType>> filterForROI( final Roi roi, final ArrayList<DifferenceOfGaussianPeak<FloatType>> peaks )
	{
		if ( roi == null )
		{
			return peaks;
		}
		else
		{
			final ArrayList<DifferenceOfGaussianPeak<FloatType>> peaksNew = new ArrayList<DifferenceOfGaussianPeak<FloatType>>();
			
			for ( final DifferenceOfGaussianPeak<FloatType> peak : peaks )
				if ( roi.contains( Math.round( peak.getSubPixelPosition( 0 ) ), Math.round( peak.getSubPixelPosition( 1 ) ) ) )
					peaksNew.add( peak );
			
			return peaksNew;
		}
	}

	protected static ArrayList<DifferenceOfGaussianPeak<FloatType>> filterForROI( final Roi roi, final ArrayList<DifferenceOfGaussianPeak<FloatType>> peaks, final Model<?> model )
	{
		if ( roi == null )
		{
			return peaks;
		}
		else
		{
			final ArrayList<DifferenceOfGaussianPeak<FloatType>> peaksNew = new ArrayList<DifferenceOfGaussianPeak<FloatType>>();
			
			// init a temporary point that we will transform
			final int numDimensions = peaks.get( 0 ).getSubPixelPosition().length;
			final float[] tmp = new float[ numDimensions ];
			
			for ( final DifferenceOfGaussianPeak<FloatType> peak : peaks )
			{
				// update tmp
				peak.getSubPixelPosition( tmp );
				
				// apply the model
				model.applyInPlace( tmp );
				
				if ( roi.contains( Math.round( tmp[ 0 ] ), Math.round( tmp[ 1 ] ) ) )
					peaksNew.add( peak );
			}
			
			return peaksNew;
		}
	}

	protected static void setPointRois( final ImagePlus imp1, final ImagePlus imp2, final ArrayList<PointMatch> inliers )
	{
		final ArrayList<Point> list1 = new ArrayList<Point>();
		final ArrayList<Point> list2 = new ArrayList<Point>();

		PointMatch.sourcePoints( inliers, list1 );
		PointMatch.targetPoints( inliers, list2 );
		
		PointRoi sourcePoints = mpicbg.ij.util.Util.pointsToPointRoi(list1);
		PointRoi targetPoints = mpicbg.ij.util.Util.pointsToPointRoi(list2);
		
		imp1.setRoi( sourcePoints );
		imp2.setRoi( targetPoints );
		
	}
	
	protected static String computeRANSAC( final ArrayList<PointMatch> candidates, final ArrayList<PointMatch> inliers, final Model<?> model, final float maxEpsilon )
	{		
		boolean modelFound = false;
		float minInlierRatio = DescriptorParameters.minInlierRatio;
		int numIterations = DescriptorParameters.ransacIterations;
		float maxTrust = DescriptorParameters.maxTrust;
		float minInlierFactor = DescriptorParameters.minInlierFactor;
		
		try
		{
			/*modelFound = m.ransac(
  					candidates,
					inliers,
					numIterations,
					maxEpsilon, minInlierRatio );*/
		
			modelFound = model.filterRansac(
					candidates,
					inliers,
					numIterations,
					maxEpsilon, minInlierRatio, maxTrust );
			
			if ( modelFound && inliers.size() > model.getMinNumMatches() * minInlierFactor )
			{
				model.fit( inliers );
				return "Remaining inliers after RANSAC (" + model.getClass().getSimpleName() + "): " + inliers.size() + " of " + candidates.size() + " with average error " + model.getCost();
			}
			else
			{
				inliers.clear();
				return "NO Model found after RANSAC (" + model.getClass().getSimpleName() + ") of " + candidates.size();
			}
		}
		catch ( Exception e )
		{
			inliers.clear();
			return "Exception - NO Model found after RANSAC (" + model.getClass().getSimpleName() + ") of " + candidates.size();
		}
	}

	protected static ArrayList<PointMatch> getCorrespondenceCandidates( final double nTimesBetter, final Matcher matcher, 
			ArrayList<DifferenceOfGaussianPeak<FloatType>> peaks1, ArrayList<DifferenceOfGaussianPeak<FloatType>> peaks2, 
			final Model<?> model, final int dimensionality, final float zStretching1, final float zStretching2 )
	{
		// two new lists
		ArrayList<Particle> listA = new ArrayList<Particle>();
		ArrayList<Particle> listB = new ArrayList<Particle>();
		
		int id = 0;
		
		if ( model == null )
		{
			// no prior model known, do a locally rigid matching
			for ( DifferenceOfGaussianPeak<FloatType> peak : peaks1 )
				listA.add( new Particle( id++, peak, zStretching1 ) );
			for ( DifferenceOfGaussianPeak<FloatType> peak : peaks2 )
				listB.add( new Particle( id++, peak, zStretching2 ) );
		}
		else
		{
			// prior model known, apply to the points before matching and then do a simple descriptor matching
			for ( DifferenceOfGaussianPeak<FloatType> peak : peaks1 )
			{
				final Particle particle = new Particle( id++, peak, zStretching1 );			
				particle.apply( model );
				for ( int d = 0; d < particle.getL().length; ++d )
					particle.getL()[ d ] = particle.getW()[ d ];
				listA.add( particle );
			}
			
			for ( DifferenceOfGaussianPeak<FloatType> peak : peaks2 )
			{
				final Particle particle = new Particle( id++, peak, zStretching2 );
				listB.add( particle );
			}
		}
		
		/* create KDTrees */	
		final KDTree< Particle > treeA = new KDTree< Particle >( listA );
		final KDTree< Particle > treeB = new KDTree< Particle >( listB );
		
		/* extract point descriptors */						
		final int numNeighbors = matcher.getRequiredNumNeighbors();
		
		final SimilarityMeasure similarityMeasure = new SquareDistance();
		
		final ArrayList< AbstractPointDescriptor > descriptorsA, descriptorsB;
		
		if ( model == null )
		{
			descriptorsA = createModelPointDescriptors( treeA, listA, numNeighbors, matcher, similarityMeasure, dimensionality );
			descriptorsB = createModelPointDescriptors( treeB, listB, numNeighbors, matcher, similarityMeasure, dimensionality );
		}
		else
		{
			descriptorsA = createSimplePointDescriptors( treeA, listA, numNeighbors, matcher, similarityMeasure );
			descriptorsB = createSimplePointDescriptors( treeB, listB, numNeighbors, matcher, similarityMeasure );
		}
		
		//IJ.log( "before" );
		//for ( final Particle p : listA )
		//{
				//0; (336.40515, 285.3888, 51.396233) [(335.1637, 293.7457, 44.46349)] {(336.40515, 285.3888, 16.24121)} <-> 467; (334.7591, 293.14224, 44.127663) [(335.1637, 293.7457, 44.46349)
		//		if ( p.getID() == 0 || p.getID() == 467 )
		//			IJ.log( p.getID() + "; " + Util.printCoordinates( p.getL() ) + " ["+Util.printCoordinates( p.getW() )+"] {" + Util.printCoordinates( p.getPeak().getSubPixelPosition() ) );
		//}
		
		/* compute matching */
		/* the list of correspondence candidates */
		final ArrayList<PointMatch> correspondenceCandidates = findCorrespondingDescriptors( descriptorsA, descriptorsB, (float)nTimesBetter );

		//IJ.log( "after" );
		//for ( final Particle p : listA )
		//{
				//0; (336.40515, 285.3888, 51.396233) [(335.1637, 293.7457, 44.46349)] {(336.40515, 285.3888, 16.24121)} <-> 467; (334.7591, 293.14224, 44.127663) [(335.1637, 293.7457, 44.46349)
		//		if ( p.getID() == 0 || p.getID() == 467 )
		//			IJ.log( p.getID() + "; " + Util.printCoordinates( p.getL() ) + " ["+Util.printCoordinates( p.getW() )+"] {" + Util.printCoordinates( p.getPeak().getSubPixelPosition() ) );
		//}

		return correspondenceCandidates;
	}
	
	protected static final ArrayList<PointMatch> findCorrespondingDescriptors( final ArrayList<AbstractPointDescriptor> descriptorsA, final ArrayList<AbstractPointDescriptor> descriptorsB, final float nTimesBetter )
	{
		final ArrayList<PointMatch> correspondenceCandidates = new ArrayList<PointMatch>();
		
		for ( final AbstractPointDescriptor descriptorA : descriptorsA )
		{
			double bestDifference = Double.MAX_VALUE;			
			double secondBestDifference = Double.MAX_VALUE;
			
			AbstractPointDescriptor bestMatch = null;
			AbstractPointDescriptor secondBestMatch = null;

			for ( final AbstractPointDescriptor descriptorB : descriptorsB )
			{
				final double difference = descriptorA.descriptorDistance( descriptorB );

				if ( difference < secondBestDifference )
				{					
					secondBestDifference = difference;
					secondBestMatch = descriptorB;
					
					if ( secondBestDifference < bestDifference )
					{
						double tmpDiff = secondBestDifference;
						AbstractPointDescriptor tmpMatch = secondBestMatch;
						
						secondBestDifference = bestDifference;
						secondBestMatch = bestMatch;
						
						bestDifference = tmpDiff;
						bestMatch = tmpMatch;
					}
				}				
			}
			
			if ( bestDifference < 100 && bestDifference * nTimesBetter < secondBestDifference )
			{	
				// add correspondence for the two basis points of the descriptor
				Particle particleA = (Particle)descriptorA.getBasisPoint();
				Particle particleB = (Particle)bestMatch.getBasisPoint();
				
				// for RANSAC
				correspondenceCandidates.add( new PointMatch( particleA, particleB ) );
			}
		}
		
		return correspondenceCandidates;
	}

	protected static ArrayList< AbstractPointDescriptor > createSimplePointDescriptors( final KDTree< Particle > tree, final ArrayList< Particle > basisPoints, 
			final int numNeighbors, final Matcher matcher, final SimilarityMeasure similarityMeasure )
	{
		final NNearestNeighborSearch< Particle > nnsearch = new NNearestNeighborSearch< Particle >( tree );
		final ArrayList< AbstractPointDescriptor > descriptors = new ArrayList< AbstractPointDescriptor > ( );
		
		for ( final Particle p : basisPoints )
		{
			final ArrayList< Particle > neighbors = new ArrayList< Particle >();
			final Particle neighborList[] = nnsearch.findNNearestNeighbors( p, numNeighbors + 1 );
			
			// the first hit is always the point itself
			for ( int n = 1; n < neighborList.length; ++n )
				neighbors.add( neighborList[ n ] );
			
			try
			{
				descriptors.add( new SimplePointDescriptor<Particle>( p, neighbors, similarityMeasure, matcher ) );
			}
			catch ( NoSuitablePointsException e )
			{
				e.printStackTrace();
			}
		}
		
		return descriptors;
	}

	protected static ArrayList< AbstractPointDescriptor > createModelPointDescriptors( final KDTree< Particle > tree, final ArrayList< Particle > basisPoints, 
			final int numNeighbors, final Matcher matcher, final SimilarityMeasure similarityMeasure, final int dimensionality )
	{
		final NNearestNeighborSearch< Particle > nnsearch = new NNearestNeighborSearch< Particle >( tree );
		final ArrayList< AbstractPointDescriptor > descriptors = new ArrayList< AbstractPointDescriptor > ( );
		
		for ( final Particle p : basisPoints )
		{
			final ArrayList< Particle > neighbors = new ArrayList< Particle >();
			final Particle neighborList[] = nnsearch.findNNearestNeighbors( p, numNeighbors + 1 );
			
			// the first hit is always the point itself
			for ( int n = 1; n < neighborList.length; ++n )
				neighbors.add( neighborList[ n ] );
			
			final TranslationInvariantModel<?> model;
			
			if ( dimensionality == 2 )
				model = new TranslationInvariantRigidModel2D();
			else if ( dimensionality == 3 )
				model = new TranslationInvariantRigidModel3D();
			else
			{
				IJ.log( "dimensionality " + dimensionality + " not supported." );
				return descriptors;
			}
				
			try
			{
				descriptors.add( new ModelPointDescriptor<Particle>( p, neighbors, model, similarityMeasure, matcher ) );
			}
			catch ( NoSuitablePointsException e )
			{
				e.printStackTrace();
			}
		}
		
		return descriptors;
	}
	
	protected static ArrayList<DifferenceOfGaussianPeak<FloatType>> computeDoG( final Image<FloatType> image, final float sigma1, final float sigma2, 
			final boolean lookForMaxima, final boolean lookForMinima, final float threshold )
	{
		return DetectionSegmentation.extractBeadsLaPlaceImgLib( image, new OutOfBoundsStrategyMirrorFactory<FloatType>(), 0.5f, sigma1, sigma2, threshold, threshold/4, lookForMaxima, lookForMinima, ViewStructure.DEBUG_MAIN );
	}
	
	protected static int[] removeInvalidAndCollectStatistics( ArrayList<DifferenceOfGaussianPeak<FloatType>> peaks )
	{
		int min = 0;
		int max = 0;

		// remove entries that are too low
        for ( int i = peaks.size() - 1; i >= 0; --i )
        {
        	final DifferenceOfGaussianPeak<FloatType> peak = peaks.get( i );
        	
        	if ( !peak.isValid() )
        		peaks.remove( i );
        	else if ( peak.getPeakType() == SpecialPoint.MIN )
				min++;
			else if ( peak.getPeakType() == SpecialPoint.MAX )
				max++;
        }
        
        return new int[]{ min, max };
	}

}
