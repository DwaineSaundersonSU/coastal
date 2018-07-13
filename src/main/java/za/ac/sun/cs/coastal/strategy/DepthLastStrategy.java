package za.ac.sun.cs.coastal.strategy;

import java.io.PrintWriter;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import za.ac.sun.cs.coastal.Configuration;
import za.ac.sun.cs.coastal.reporting.Reporters;
import za.ac.sun.cs.coastal.symbolic.SegmentedPC;
import za.ac.sun.cs.coastal.symbolic.SymbolicState;
import za.ac.sun.cs.green.Green;
import za.ac.sun.cs.green.Instance;
import za.ac.sun.cs.green.expr.Constant;
import za.ac.sun.cs.green.expr.Expression;
import za.ac.sun.cs.green.expr.IntConstant;
import za.ac.sun.cs.green.expr.IntVariable;
import za.ac.sun.cs.green.service.ModelCoreService;

public class DepthLastStrategy implements Strategy {

	private static final Logger lgr = Configuration.getLogger();

	private static final boolean dumpTrace = Configuration.getDumpTrace();

	private static final Logger greenLgr = LogManager.getLogger("GREEN");

	private static final Green green = new Green("COASTAL", greenLgr);

	private static final Set<String> visitedModels = new HashSet<>();

	private static int infeasibleCount = 0;

	private static final DLPathTree pathTree = new DLPathTree(); 

	private long pathLimit = 0;

	private long totalTime = 0, solverTime = 0, pathTreeTime = 0, modelExtractionTime = 0;

	public DepthLastStrategy() {
		Reporters.register(this);
		Properties greenProperties = Configuration.getProperties();
		greenProperties.setProperty("green.log.level", "ALL");
		greenProperties.setProperty("green.services", "model");
		greenProperties.setProperty("green.service.model", "(bounder modeller)");
		greenProperties.setProperty("green.service.model.bounder", "za.ac.sun.cs.green.service.bounder.BounderService");
		greenProperties.setProperty("green.service.model.modeller", "za.ac.sun.cs.green.service.z3.ModelCoreZ3Service");
		// greenProperties.setProperty("green.store", "za.ac.sun.cs.green.store.redis.RedisStore");
		new za.ac.sun.cs.green.util.Configuration(green, greenProperties).configure();
		pathLimit = Configuration.getLimitPaths();
		if (pathLimit == 0) {
			pathLimit = Long.MIN_VALUE;
		}
	}
	
	@Override
	public Map<String, Constant> refine() {
		long t0 = System.currentTimeMillis();
		Map<String, Constant> refinement = refine0();
		totalTime += System.currentTimeMillis() - t0;
		return refinement;
	}

	private Map<String, Constant> refine0() {
		long t;
		SegmentedPC spc = SymbolicState.getSegmentedPathCondition();
		// lgr.info("explored <{}> {}", spc.getSignature(), SegmentedPC.constraintBeautify(spc.getPathCondition().toString()));
		lgr.info("explored <{}> {}", spc.getSignature(), spc.getPathCondition().toString());
		boolean infeasible = false;
		while (true) {
			if (--pathLimit < 0) {
				lgr.warn("path limit reached");
				return null;
			}
			t = System.currentTimeMillis();
			spc = pathTree.insertPath(spc, infeasible);
			pathTreeTime += System.currentTimeMillis() - t;
			if (spc == null) {
				lgr.info("no further paths");
				if (dumpTrace) {
					lgr.info("Tree shape: {}", pathTree.getShape());
				}
				return null;
			}
			infeasible = false;
			Expression pc = spc.getPathCondition();
			String sig = spc.getSignature();
			// lgr.info("trying   <{}> {}", sig, SegmentedPC.constraintBeautify(pc.toString()));
			lgr.info("trying   <{}> {}", sig, pc.toString());
			Instance instance = new Instance(green, null, pc);
			t = System.currentTimeMillis();
			Instance result = (Instance) instance.request("model");
			@SuppressWarnings("unchecked")
			Map<IntVariable, IntConstant> model = (Map<IntVariable, IntConstant>) result.getData(ModelCoreService.MODEL_KEY);
			solverTime += System.currentTimeMillis() - t;
			if (model == null) {
				lgr.info("no model");
				if (dumpTrace) {
					lgr.info("(The spc is {})", spc.getPathCondition().toString());
				}
				infeasible = true;
				infeasibleCount++;
			} else {
				t = System.currentTimeMillis();
				Map<String, Constant> newModel = new HashMap<>();
				for (IntVariable variable : model.keySet()) {
					String name = variable.getName();
					if (name.startsWith(SymbolicState.NEW_VAR_PREFIX)) {
						continue;
					}
					Constant value = model.get(variable);
					newModel.put(name, value);
				}
				String modelString = newModel.toString();
				modelExtractionTime += System.currentTimeMillis() - t;
				// lgr.info("new model: {}", SegmentedPC.modelBeautify(modelString));
				lgr.info("new model: {}", modelString);
				if (visitedModels.add(modelString)) {
					return newModel;
				} else {
					lgr.info("model {} has been visited before, retrying", modelString);
					/* OLD CODE, WAS ALMOST CERTAINLY BUGGY:
					t = System.currentTimeMillis();
					spc = PathTree.insertPath(spc, false);
					pathTreeTime += System.currentTimeMillis() - t;
					*/
				}
			}
		}
	}

	// ======================================================================
	//
	// DFPathTree
	//
	// ======================================================================

	private static class DLPathTree extends PathTree {
		@Override
		public SegmentedPC findNewPath() {
			SegmentedPC newSpc = null;
			PathTreeNode cur = getRoot();
			while (true) {
				if (cur.getRight() == null) {
					if (dumpPaths) {
						lgr.debug("  At {} (dead end), generating negate path (R)", getId(cur));
					}
					newSpc = new SegmentedPC(newSpc, cur.getActiveConjunct(), cur.getPassiveConjunct(), true);
					return newSpc;
				} else if (cur.getLeft() == null) {
					if (dumpPaths) {
						lgr.debug("  At {} (dead end), generating negate path (L)", getId(cur));
					}
					newSpc = new SegmentedPC(newSpc, cur.getActiveConjunct(), cur.getPassiveConjunct(), false);
					return newSpc;
				} else if ((cur.getRight() != null) && !cur.getRight().isComplete()) {
					if (dumpPaths) {
						lgr.debug("  At {}, right is available", getId(cur));
					}
					newSpc = new SegmentedPC(newSpc, cur.getActiveConjunct(), cur.getPassiveConjunct(), true);
					cur = cur.getRight();
				} else if ((cur.getLeft() != null) && !cur.getLeft().isComplete()) {
					if (dumpPaths) {
						lgr.debug("  At {}, left is available", getId(cur));
					}
					newSpc = new SegmentedPC(newSpc, cur.getActiveConjunct(), cur.getPassiveConjunct(), false);
					cur = cur.getLeft();
				} else {
					if (dumpPaths) {
						lgr.debug("  At #{} (dead end), no more paths", getId(cur));
					}
					return null;
				}
			}
		}
	}

	// ======================================================================
	//
	// REPORTING
	//
	// ======================================================================

	@Override
	public String getName() {
		return "DepthLastStrategy";
	}

	@Override
	public void report(PrintWriter out) {
		out.println("  Inserted paths: " + pathTree.getPathCount());
		out.println("  Revisited paths: " + pathTree.getRevisitCount());
		out.println("  Infeasible paths: " + infeasibleCount);
		out.println("  Solver time: " + solverTime);
		out.println("  Path tree time: " + pathTreeTime);
		out.println("  Model extraction time: " + modelExtractionTime);
		out.println("  Overall strategy time: " + totalTime);
	}

}
