package za.ac.sun.cs.coastal.strategy.pathbased;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import za.ac.sun.cs.coastal.COASTAL;
import za.ac.sun.cs.coastal.strategy.Strategy;
import za.ac.sun.cs.coastal.strategy.StrategyFactory;
import za.ac.sun.cs.coastal.strategy.StrategyManager;
import za.ac.sun.cs.coastal.symbolic.Model;
import za.ac.sun.cs.coastal.symbolic.SegmentedPC;
import za.ac.sun.cs.coastal.symbolic.SegmentedPCIf;
import za.ac.sun.cs.green.expr.Constant;
import za.ac.sun.cs.green.expr.Expression;

public class GenerationalFactory implements StrategyFactory {

	public GenerationalFactory(COASTAL coastal) {
	}

	@Override
	public StrategyManager createManager(COASTAL coastal) {
		return new GenerationalManager(coastal);
	}

	@Override
	public Strategy createStrategy(COASTAL coastal, StrategyManager manager) {
		if (((GenerationalManager) manager).full) {
			return new GenerationalFullStrategy(coastal, manager);
		} else {
			return new GenerationalStrategy(coastal, manager);
		}
	}
	
	// ======================================================================
	//
	// SPECIFIC MANAGER
	//
	// ======================================================================
	
	private static class GenerationalManager extends PathBasedManager {

		private final int priorityStart;

		private final int priorityDelta;

		private final boolean full;
	
		GenerationalManager(COASTAL coastal) {
			super(coastal);
			if (coastal.getConfig().getBoolean("coastal.strategy[@topdown]", false)) {
				priorityStart = 0;
				priorityDelta = 1;
			} else {
				priorityStart = 10000;
				priorityDelta = -1;
			}
			full = coastal.getConfig().getBoolean("coastal.strategy[@full]", true);
		}

	}

	// ======================================================================
	//
	// SPECIFIC STRATEGY FOR FULL PATH CONDITIONS
	//
	// ======================================================================

	private static class GenerationalFullStrategy extends PathBasedStrategy {

		private final int priorityStart;

		private final int priorityDelta;
		
		GenerationalFullStrategy(COASTAL coastal, StrategyManager manager) {
			super(coastal, manager);
			priorityStart = ((GenerationalManager) manager).priorityStart;
			priorityDelta = ((GenerationalManager) manager).priorityDelta;
		}

		@Override
		protected List<Model> refine0(SegmentedPC spc) {
			if ((spc == null) || (spc == SegmentedPC.NULL)) {
				return null;
			}
			List<Model> models = new ArrayList<>();
			log.info("explored <{}> {}", spc.getSignature(), spc.getPathCondition().toString());
			if (!manager.insertPath(spc, false)) {
				List<SegmentedPC> altSpcs = new ArrayList<>();
				for (SegmentedPC pointer = spc; pointer != null; pointer = pointer.getParent()) {
					altSpcs.add(generateAltSpc(spc, pointer));
				}
				int priority = priorityStart;
				for (SegmentedPC altSpc : altSpcs) {
					Expression pc = altSpc.getPathCondition();
					String sig = altSpc.getSignature();
					log.info("trying   <{}> {}", sig, pc.toString());
					Map<String, Constant> model = findModel(pc);
					if (model == null) {
						log.info("no model");
						log.trace("(The spc is {})", altSpc.getPathCondition().toString());
						manager.insertPath(altSpc, true);
						manager.incrementInfeasibleCount();
					} else {
						String modelString = model.toString();
						log.info("new model: {}", modelString);
						if (visitedModels.add(modelString)) {
							models.add(new Model(priority, model));
							priority += priorityDelta;
						} else {
							log.info("model {} has been visited before", modelString);
						}
					}
				}
			} else {
				log.info("revisited path -- no new models generated");
			}
			return models;
		}

		private SegmentedPC generateAltSpc(SegmentedPC spc, SegmentedPC pointer) {
			SegmentedPC parent = null;
			boolean value = ((SegmentedPCIf) spc).getValue();
			if (spc == pointer) {
				parent = spc.getParent();
				value = !value;
			} else {
				parent = generateAltSpc(spc.getParent(), pointer);
			}
			return new SegmentedPCIf(parent, spc.getExpression(), spc.getPassiveConjunct(), value);
		}

		@Override
		protected SegmentedPC findNewPath(PathTree pathTree) {
			return null;
		}

	}

	// ======================================================================
	//
	// SPECIFIC STRATEGY FOR TRUNCATED PATH CONDITIONS
	//
	// ======================================================================
	
	private static class GenerationalStrategy extends PathBasedStrategy {
		
		private final int priorityStart;
		
		private final int priorityDelta;
		
		GenerationalStrategy(COASTAL coastal, StrategyManager manager) {
			super(coastal, manager);
			priorityStart = ((GenerationalManager) manager).priorityStart;
			priorityDelta = ((GenerationalManager) manager).priorityDelta;
		}
		
		@Override
		protected List<Model> refine0(SegmentedPC spc) {
			if ((spc == null) || (spc == SegmentedPC.NULL)) {
				return null;
			}
			List<Model> models = new ArrayList<>();
			log.info("explored <{}> {}", spc.getSignature(), spc.getPathCondition().toString());
			if (!manager.insertPath(spc, false)) {
				List<SegmentedPC> altSpcs = new ArrayList<>();
				for (SegmentedPC pointer = spc; pointer != null; pointer = pointer.getParent()) {
					altSpcs.add(generateAltSpc(pointer));
				}
				int priority = priorityStart;
				for (SegmentedPC altSpc : altSpcs) {
					Expression pc = altSpc.getPathCondition();
					String sig = altSpc.getSignature();
					log.info("trying   <{}> {}", sig, pc.toString());
					Map<String, Constant> model = findModel(pc);
					if (model == null) {
						log.info("no model");
						log.trace("(The spc is {})", altSpc.getPathCondition().toString());
						manager.insertPath(altSpc, true);
						manager.incrementInfeasibleCount();
					} else {
						String modelString = model.toString();
						log.info("new model: {}", modelString);
						if (visitedModels.add(modelString)) {
							models.add(new Model(priority, model));
							priority += priorityDelta;
						} else {
							log.info("model {} has been visited before", modelString);
						}
					}
				}
			} else {
				log.info("revisited path -- no new models generated");
			}
			return models;
		}
		
		private SegmentedPC generateAltSpc(SegmentedPC pointer) {
			SegmentedPC parent = pointer.getParent();
			Expression pc = pointer.getExpression();
			Expression passive = pointer.getPassiveConjunct();
			boolean value = ((SegmentedPCIf) pointer).getValue();
			return new SegmentedPCIf(parent, pc, passive, !value);
		}
		
		@Override
		protected SegmentedPC findNewPath(PathTree pathTree) {
			return null;
		}
		
	}
	
}