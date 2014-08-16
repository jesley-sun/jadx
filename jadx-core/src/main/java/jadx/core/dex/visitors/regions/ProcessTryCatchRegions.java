package jadx.core.dex.visitors.regions;

import jadx.core.dex.attributes.AFlag;
import jadx.core.dex.attributes.AType;
import jadx.core.dex.nodes.BlockNode;
import jadx.core.dex.nodes.IContainer;
import jadx.core.dex.nodes.IRegion;
import jadx.core.dex.nodes.MethodNode;
import jadx.core.dex.regions.AbstractRegion;
import jadx.core.dex.regions.Region;
import jadx.core.dex.trycatch.CatchAttr;
import jadx.core.dex.trycatch.ExceptionHandler;
import jadx.core.dex.trycatch.TryCatchBlock;
import jadx.core.utils.BlockUtils;
import jadx.core.utils.RegionUtils;
import jadx.core.utils.exceptions.JadxRuntimeException;

import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Extract blocks to separate try/catch region
 */
public class ProcessTryCatchRegions extends AbstractRegionVisitor {

	private static final Logger LOG = LoggerFactory.getLogger(ProcessTryCatchRegions.class);

	public static void process(MethodNode mth) {
		if (mth.isNoCode() || mth.isNoExceptionHandlers()) {
			return;
		}

		final Map<BlockNode, TryCatchBlock> tryBlocksMap = new HashMap<BlockNode, TryCatchBlock>(2);
		searchTryCatchDominators(mth, tryBlocksMap);

		int k = 0;
		while (!tryBlocksMap.isEmpty()) {
			DepthRegionTraversal.traverseAll(mth, new AbstractRegionVisitor() {
				@Override
				public void leaveRegion(MethodNode mth, IRegion region) {
					checkAndWrap(mth, tryBlocksMap, region);
				}
			});
			if (k++ > 100) {
				throw new JadxRuntimeException("Try/catch wrap count limit reached in " + mth);
			}
		}
	}

	private static void searchTryCatchDominators(MethodNode mth, Map<BlockNode, TryCatchBlock> tryBlocksMap) {
		final Set<TryCatchBlock> tryBlocks = new HashSet<TryCatchBlock>();
		// collect all try/catch blocks
		for (BlockNode block : mth.getBasicBlocks()) {
			CatchAttr c = block.get(AType.CATCH_BLOCK);
			if (c != null) {
				tryBlocks.add(c.getTryBlock());
			}
		}

		// for each try block search nearest dominator block
		for (TryCatchBlock tb : tryBlocks) {
			BitSet bs = null;
			// build bitset with dominators of blocks covered with this try/catch block
			for (BlockNode block : mth.getBasicBlocks()) {
				CatchAttr c = block.get(AType.CATCH_BLOCK);
				if (c != null && c.getTryBlock() == tb) {
					if (bs == null) {
						bs = (BitSet) block.getDoms().clone();
					} else {
						bs.and(block.getDoms());
					}
				}
			}

			// intersect to get dominator of dominators
			List<BlockNode> domBlocks = BlockUtils.bitSetToBlocks(mth, bs);
			for (BlockNode block : domBlocks) {
				bs.andNot(block.getDoms());
			}
			domBlocks = BlockUtils.bitSetToBlocks(mth, bs);
			if (domBlocks.size() != 1) {
				throw new JadxRuntimeException(
						"Exception block dominator not found, method:" + mth + ". bs: " + bs);
			}

			BlockNode domBlock = domBlocks.get(0);

			TryCatchBlock prevTB = tryBlocksMap.put(domBlock, tb);
			if (prevTB != null) {
				LOG.info("!!! TODO: merge try blocks in " + mth);
			}
		}
	}

	private static void checkAndWrap(MethodNode mth, Map<BlockNode, TryCatchBlock> tryBlocksMap, IRegion region) {
		// search dominator blocks in this region (don't need to go deeper)
		for (Map.Entry<BlockNode, TryCatchBlock> entry : tryBlocksMap.entrySet()) {
			BlockNode dominator = entry.getKey();
			if (region.getSubBlocks().contains(dominator)) {
				TryCatchBlock tb = tryBlocksMap.get(dominator);
				if (!wrapBlocks(region, tb, dominator)) {
					LOG.warn("Can't wrap try/catch for {}, method: {}", dominator, mth);
					mth.add(AFlag.INCONSISTENT_CODE);
				}
				tryBlocksMap.remove(dominator);
				return;
			}
		}
	}

	/**
	 * Extract all block dominated by 'dominator' to separate region and mark as try/catch block
	 */
	private static boolean wrapBlocks(IRegion region, TryCatchBlock tb, BlockNode dominator) {
		Region newRegion = new Region(region);
		List<IContainer> subBlocks = region.getSubBlocks();
		for (IContainer cont : subBlocks) {
			if (RegionUtils.isDominatedBy(dominator, cont)) {
				if (isHandlerPath(tb, cont)) {
					break;
				}
				newRegion.getSubBlocks().add(cont);
			}
		}
		if (newRegion.getSubBlocks().isEmpty()) {
			return false;
		}
		// replace first node by region
		IContainer firstNode = newRegion.getSubBlocks().get(0);
		if (!region.replaceSubBlock(firstNode, newRegion)) {
			return false;
		}
		subBlocks.removeAll(newRegion.getSubBlocks());

		newRegion.addAttr(tb.getCatchAttr());

		// fix parents
		for (IContainer cont : newRegion.getSubBlocks()) {
			if (cont instanceof AbstractRegion) {
				AbstractRegion aReg = (AbstractRegion) cont;
				aReg.setParent(newRegion);
			}
		}
		return true;
	}

	private static boolean isHandlerPath(TryCatchBlock tb, IContainer cont) {
		for (ExceptionHandler h : tb.getHandlers()) {
			if (RegionUtils.hasPathThruBlock(h.getHandlerBlock(), cont)) {
				return true;
			}
		}
		return false;
	}
}