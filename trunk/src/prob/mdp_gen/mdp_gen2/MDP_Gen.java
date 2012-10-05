//////////////////////////////////////////////////////////////////////////
//
// File:     MDP.java
// Author:   Scott Sanner, University of Toronto (ssanner@cs.toronto.edu)
// Date:     9/1/2003
//
// Description:
//
//   An MDP inference package that uses both Tables, ADDs, AADDs as the
//   underlying computational mechanism.  (All via the logic.add.FBR
//   interface.)  See SPUDD (Hoey et al, UAI 1999) for more details on the
//   algorithm.
//
//////////////////////////////////////////////////////////////////////////

// Package definition
package prob.mdp_gen;

// Packages to import
import graph.Graph;

import java.io.*;
import java.math.*;
import java.text.*;
import java.util.regex.*;
import java.util.*;

// DD & FBR interfaces
import logic.add_gen.*;

/**
 * Main MDP inference class
 * 
 * @version 1.0
 * @author Scott Sanner
 * @language Java (JDK 1.3)
 **/
public class MDP_Gen {

	////////////////////////////////////////////////////////////////////////////
	// /
	// Class Constants and Data Members
	////////////////////////////////////////////////////////////////////////////
	// /

	/* Local constants */
	public final static boolean DISPLAY_REW = false;
	public final static boolean DISPLAY_VAL = false;
	public final static boolean DISPLAY_QVAL = false;

	public final static int VERBOSE_LEVEL = 0; // Determines how much output is
												// displayed
	public final static boolean ALWAYS_FLUSH = false; // Always flush DD caches?
	public final static double FLUSH_PERCENT_MINIMUM = 0.3d; // Won't flush
																// until < this
																// amt

	/* For printing */
	public static DecimalFormat _df = new DecimalFormat("#.###");

	/* Static variables */
	public static long _lTime; // For timing purposes
	public static Runtime RUNTIME = Runtime.getRuntime();

	/* Local vars */
	public ArrayList _alVars; // List of variable names (including primes) index
								// is ID
	public TreeMap _tmID2Var; // Maps names -> Integers (including primes
								// a',b',etc...)
	public TreeMap _tmVar2ID; // Maps names -> Integers (including primes
								// a',b',etc...)
	public HashMap _hmPrimeRemap; // Maps non-prime GIDs to their primed
									// counterparts
	public ArrayList _alOrder; // The variable order used in decision diagrams
	public Map _hmName2Action; // List of actions (see Action.java)
	public FBR _context;
	public Object _rewardDD; // The reward for this MDP
	public Object _valueDD; // The resulting value function once this MDP has
							// been solved
	public Object _maxDD;
	public Object _prevDD;
	public BigDecimal _bdDiscount; // Discount (gamma) for MDP
	public BigDecimal _bdTolerance; // Tolerance (gamma) for MDP
	public int _nDDType; // Type of DD to use
	public TreeMap _tmAct2Regr; // Cached DDs from last regression step
	public int _nIter;
	public int _nMaxRegrSz;
	public double _dRewardRange;
	public String _sRegrAction;
	public ArrayList _alSaveNodes; // Nodes to save during cache flushing

	////////////////////////////////////////////////////////////////////////////
	// /
	// Constructors
	////////////////////////////////////////////////////////////////////////////
	// /

	/**
	 * Constructor - filename
	 **/
	public MDP_Gen(String filename, int dd_type) {
		this(HierarchicalParser.ParseFile(filename), dd_type);
	}

	/**
	 * Constructor - pre-parsed file
	 **/
	public MDP_Gen(ArrayList input, int dd_type) {
		_prevDD = _maxDD = _rewardDD = _valueDD = null;
		_nDDType = dd_type;
		_alVars = new ArrayList();
		_alOrder = new ArrayList();
		_tmVar2ID = new TreeMap();
		_tmID2Var = new TreeMap();
		_tmAct2Regr = new TreeMap();
		_hmPrimeRemap = new HashMap();
		_hmName2Action = new TreeMap();
		_alSaveNodes = new ArrayList();
		_bdDiscount = new BigDecimal("" + (-1));
		_bdTolerance = new BigDecimal("" + (-1));
		_nIter = -1;
		_sRegrAction = null;
		_nMaxRegrSz = -1;

		buildMDP(input);
		
		_dRewardRange = _context.getMaxValue(_rewardDD) - 
						_context.getMinValue(_rewardDD);
	}

	////////////////////////////////////////////////////////////////////////////
	// /
	// Generic MDP Inference Methods
	////////////////////////////////////////////////////////////////////////////
	// /

	/**
	 * MDP inference methods
	 **/
	public int solve(double precision, int prune_type, double prune_strength /*decimal, max=1.0*/) {

		// Result goes in _valueDD
		int max_iter = 0;
		boolean b_iter = false;
		if (precision >= 1.0d) {
			b_iter = true;
			max_iter = (int) precision;
		}


		// ////////////////////////////////////////////////////////////
		// Set value function equal to reward
		// ////////////////////////////////////////////////////////////
		_valueDD = _rewardDD;
		double cur_prune_strength = _bdDiscount.doubleValue() == 1.0 
				? prune_strength * _dRewardRange : 0d;
		
		// Other initialization
		int iter = 0;
		double max_diff = Double.POSITIVE_INFINITY;
		double tolerance = _bdTolerance.doubleValue();
		boolean error_decreasing = true;
		System.out.println("Using discount:  " + _bdDiscount);
		System.out.println("Using tolerance: " + tolerance + "\n");

		// ////////////////////////////////////////////////////////////
		// Iterate until convergence (or max iterations)
		// ////////////////////////////////////////////////////////////
		while ((max_diff >= tolerance) /* convergence */
				&& (b_iter && (iter < max_iter)) /* iteration check */) {

			_nIter = iter;
			cur_prune_strength = _bdDiscount.doubleValue() == 1.0 
				? prune_strength * _dRewardRange
				: _bdDiscount.doubleValue() * cur_prune_strength + 
				  prune_strength * _dRewardRange;
			FBR.SetPruneInfo(prune_type, cur_prune_strength);

			// Cache maintenance
			flushCaches();

			// Error decreasing?
			System.out.print(error_decreasing ? "  " : "* ");
			System.out.println("Iteration #" + iter + ", "
					+ _context.countExactNodes(_valueDD) + " nodes / "
					+ _context.getCacheSize() + " cache / " + MemDisplay()
					+ " bytes " + "[" + _df.format(max_diff) + "], mr:["
					+ _df.format(_context.getMaxValue(_valueDD)) + "]");

			// Flush cache now to prevent accumulation of src links
			// Runtime.getRuntime().gc();

			// Prime the value function diagram so it is in terms of next state
			// vars!
			_prevDD = _valueDD;
			_valueDD = _context.remapGIDsInt(_valueDD, _hmPrimeRemap);

			// ////////////////////////////////////////////////////////////
			// Iterate over each action
			// ////////////////////////////////////////////////////////////
			_maxDD = null;
			Iterator i = _hmName2Action.entrySet().iterator();
			_tmAct2Regr.clear();
			while (i.hasNext()) {

				Map.Entry me = (Map.Entry) i.next();
				Action a = (Action) me.getValue();
				_sRegrAction = (String) me.getKey();

				// ////////////////////////////////////////////////////////////
				// Regress the current value function through each action
				// ////////////////////////////////////////////////////////////
				Object regr = regress(_valueDD, a);

				if (DISPLAY_QVAL) {
					Graph g = _context.getGraph(regr);
					g.addNodeLabel("_temp_", a._sName);
					g.addNodeShape("_temp_", "square");
					g.addNodeStyle("_temp_", "filled");
					g.addNodeColor("_temp_", "lightblue");
					g.addUniLink("_temp_", "_temp_");

					// g.genDotFile(type + "value.dot");
					g.launchViewer(1300, 770);
				}

				// Cache maintenance
				clearSaveNodes();
				saveNode(regr);
				flushCaches();

				// Screen output
				if (VERBOSE_LEVEL >= 1) {
					System.out.println("  - After regress '" + a._sName + "', "
							+ _context.countExactNodes(regr) + " nodes / "
							+ _context.getCacheSize() + " cache");
				}

				// In case comparing last regressions, uncomment the following
				// _tmAct2Regr.put(a._sName, regr);

				// ////////////////////////////////////////////////////////////
				// Take the max over this action and the previous action
				// ////////////////////////////////////////////////////////////
				_maxDD = ((_maxDD == null) ? regr : _context.applyInt(_maxDD,
						regr, DD.ARITH_MAX));

				// Cache maintance
				flushCaches();

				// Screen output
				if (VERBOSE_LEVEL >= 1) {
					System.out.println("  - After max '" + a._sName + "', "
							+ _context.countExactNodes(_maxDD) + " nodes / "
							+ _context.getCacheSize() + " cache");
				}
			}

			// ////////////////////////////////////////////////////////////
			// Discount the max'ed value function backup and add in reward
			// ////////////////////////////////////////////////////////////
			_valueDD = _context.applyInt(_rewardDD, _context.scalarMultiply(
					_maxDD, _bdDiscount.doubleValue()), DD.ARITH_SUM);

			// Screen output
			if (VERBOSE_LEVEL >= 1) {
				System.out.println("\n  - After sum, "
						+ _context.countExactNodes(_valueDD) + " nodes / "
						+ _context.getCacheSize() + " cache");
			}

			////////////////////////////////////////////////////////////////////
			// ////
			// TODO: Prune?
			////////////////////////////////////////////////////////////////////
			// ////
			long size_before = _context.countExactNodes(_valueDD);
			Object dd_before = _valueDD;
			_valueDD = _context.pruneNodes(_valueDD);
			long size_after = _context.countExactNodes(_valueDD);
			if (size_after > size_before) {
				System.out
						.println("WARNING: SIZE DID NOT DECREASE ON PRUNING, NO PRUNE.");
				_valueDD = dd_before;
				/*
				 * Graph g = _context.getGraph(dd_before);
				 * //g.genDotFile("before.dot"); g.launchViewer(1300, 770);
				 * Graph g2 = _context.getGraph(_valueDD);
				 * //g2.genDotFile("after.dot"); g2.launchViewer(1300, 770);
				 * 
				 * // Pause indefinitely... System.exit(1) would kill viewer try
				 * { Runtime.getRuntime().wait(1000000000); } catch (Exception
				 * e) {}
				 */
			}

			////////////////////////////////////////////////////////////////////
			// /
			// Compute max difference between current and previous value
			// function
			////////////////////////////////////////////////////////////////////
			// /
			Object diff = _context.applyInt(_valueDD, _prevDD, DD.ARITH_MINUS);
			double max_diff_prev = max_diff;
			double max_pos_diff = _context.getMaxValue(diff);
			double max_neg_diff = _context.getMinValue(diff);
			max_diff = Math.max(Math.abs(max_pos_diff), Math.abs(max_neg_diff));
			error_decreasing = (max_diff < max_diff_prev);

			// Screen output
			if (VERBOSE_LEVEL >= 1) {
				
				if (VERBOSE_LEVEL >= 2) {
					Graph g1 = _context.getGraph(_valueDD);
					g1.launchViewer(1300, 770);
					Graph g2 = _context.getGraph(_prevDD);
					g2.launchViewer(1300, 770);
				}
				
				System.out.println("\n  - Max diff: "
						+ _df.format(max_diff));
			}

			// Increment counter
			iter++;
		}

		// Flush caches and return number of iterations
		flushCaches();
		return iter;
	}

	/**
	 * Regress a DD through an action
	 **/
	public Object regress(Object vfun, Action a) {
		return regress(vfun, a, true);
	}
	
	public Object regress(Object vfun, Action a, boolean flush_caches) {

		// For every next-state var in Action, multiply by DD and sumOut var
		long max = -1;
		Iterator i = a._tmID2DD.entrySet().iterator();
		Object dd_ret = vfun;

		// Find what gids are currently in vfun (probs cannot introduce new
		// primed gids)
		Set gids = _context.getGIDs(vfun);
		if (VERBOSE_LEVEL >= 1) {
			System.out.println("Regressing action: " + a._sName + "\nGIDs: "
					+ gids);
		}

		// ////////////////////////////////////////////////////////////
		// For each next state variable in DBN for action 'a'
		// ////////////////////////////////////////////////////////////
		while (i.hasNext()) {

			Map.Entry me = (Map.Entry) i.next();
			Integer head_id = (Integer) me.getKey();

			// No use in multiplying by a gid that does not exist (and will sum
			// to 1)
			if (!gids.contains(head_id)) {
				if (VERBOSE_LEVEL >= 1) {
					System.out.println("Skipping " + head_id);
				}
				continue;
			}

			// Get the dd for this action
			Object dd = me.getValue();

			// Screen output
			if (VERBOSE_LEVEL >= 2) {
				System.out.println("  - Summing out: " + head_id);
			}

			// /////////////////////////////////////////////////////////////////
			// Multiply next state variable DBN into current value function
			// /////////////////////////////////////////////////////////////////
			dd_ret = _context.applyInt(dd_ret, dd, DD.ARITH_PROD);
			int regr_sz = _context.getGIDs(dd_ret).size();
			if (regr_sz > _nMaxRegrSz) {
				_nMaxRegrSz = regr_sz;
			}

			// /////////////////////////////////////////////////////////////////
			// Sum out next state variable
			// /////////////////////////////////////////////////////////////////
			dd_ret = _context.opOut(dd_ret, head_id.intValue(), DD.ARITH_SUM); // CHANGED
																				// -
																				// 11
																				// -
																				// 17
																				// -
																				// 04

			// Cache maintenance
			if (flush_caches) {
				clearSaveNodes();
				saveNode(dd_ret);
				flushCaches();
			}
		}

		// Return regressed value function (which is now in terms of prev state
		// vars)
		return dd_ret;
	}
	
	////////////////////////////////////////////////////////////////////////////
	// /
	// DD Cache Maintenance
	////////////////////////////////////////////////////////////////////////////
	// /

	/**
	 * Clear nodes on save list
	 **/
	public void clearSaveNodes() {
		_alSaveNodes.clear();
	}

	/**
	 * Add node to save list
	 **/
	public void saveNode(Object dd) {
		_alSaveNodes.add(dd);
	}

	/**
	 * Frees up memory... only do this if near limit?
	 **/
	public void flushCaches() {
		if (!ALWAYS_FLUSH
				&& ((double) RUNTIME.freeMemory() / (double) RUNTIME
						.totalMemory()) > FLUSH_PERCENT_MINIMUM) {
			return; // Still enough free mem to exceed minimum requirements
		}

		_context.clearSpecialNodes();
		Iterator i = _hmName2Action.values().iterator();
		while (i.hasNext()) {
			Action a = (Action) i.next();
			Iterator j = a._hsTransDDs.iterator();
			while (j.hasNext()) {
				_context.addSpecialNode(j.next());
			}
		}
		_context.addSpecialNode(_rewardDD);
		_context.addSpecialNode(_valueDD);
		if (_maxDD != null)
			_context.addSpecialNode(_maxDD);
		if (_prevDD != null)
			_context.addSpecialNode(_prevDD);

		Iterator j = _alSaveNodes.iterator();
		while (j.hasNext()) {
			_context.addSpecialNode(j.next());
		}
		_context.flushCaches(false);
	}

	public double getRewardRange() {
		return _context.getMaxValue(_rewardDD)
				- _context.getMinValue(_rewardDD);
	}

	////////////////////////////////////////////////////////////////////////////
	// /
	// MDP Construction Methods
	////////////////////////////////////////////////////////////////////////////
	// /

	/**
	 * MDP construction methods
	 **/
	public void buildMDP(ArrayList input) {

		if (input == null) {
			System.out.println("Empty input file!");
			System.exit(1);
		}

		Iterator i = input.iterator();
		Object o;

		// Set up variables
		o = i.next();
		if (!(o instanceof String)
				|| !((String) o).equalsIgnoreCase("variables")) {
			System.out.println("Missing variable declarations: " + o);
			System.exit(1);
		}
		o = i.next();
		int id_count = 1;
		_alVars = (ArrayList) ((ArrayList) o).clone();
		Iterator vars = _alVars.iterator();
		while (vars.hasNext()) {
			String vname = ((String) vars.next()) + "'";
			_tmID2Var.put(new Integer(id_count), vname);
			_tmVar2ID.put(vname, new Integer(id_count));
			_alOrder.add(new Integer(id_count));
			++id_count;
		}
		int nvars = _alOrder.size();
		vars = _alVars.iterator();
		while (vars.hasNext()) {
			String vname = ((String) vars.next());
			_tmID2Var.put(new Integer(id_count), vname);
			_tmVar2ID.put(vname, new Integer(id_count));
			_alOrder.add(new Integer(id_count));
			_hmPrimeRemap.put(new Integer(id_count), new Integer(id_count
					- nvars));
			++id_count;
		}
		_context = new FBR(_nDDType, _alOrder);
		// System.out.println("Remap: " + _hmPrimeRemap);
		// System.exit(1);

		// Set up actions
		while (true) {
			o = i.next();
			if (!(o instanceof String)
					|| !((String) o).equalsIgnoreCase("action")) {
				break;
			}

			// o == "action"
			String aname = (String) i.next();
			HashMap cpt_map = new HashMap();

			o = i.next();
			while (!((String) o).equalsIgnoreCase("endaction")) {
				cpt_map.put((String) o + "'", (ArrayList) i.next());
				o = i.next();
			}

			_hmName2Action.put(aname, new Action(this, aname, cpt_map));
		}

		// Set up reward
		if (!(o instanceof String) || !((String) o).equalsIgnoreCase("reward")) {
			System.out.println("Missing reward declaration: " + o);
			System.exit(1);
		}
		System.out.println("==========================================");
		ArrayList reward = (ArrayList) i.next();
		// System.out.println(reward);
		// System.out.println(reward);
		// AADD.PRINT_DEBUG = AADD.PRINT_APPLY = AADD.PRINTING_ON = true;
		_rewardDD = _context.buildDDFromUnorderedTree(reward, _tmVar2ID);
		// AADD.PRINT_DEBUG = AADD.PRINT_APPLY = AADD.PRINTING_ON = false;
		if (DD.PRUNE_TYPE == DD.REPLACE_RANGE) {
			System.out.println("MDP: PruneReward not implemented");
			System.exit(1);
			// System.out.println("Pruning reward...");
			// TODO: _context.pruneNodes(_rewardDD);
		}
		// System.out.println("==========================================");
		// System.out.println("Reward: " + _context.printNode(_rewardDD));
		// System.out.println("==========================================");
		// Graph g = _context.getGraph(_rewardDD);
		// g.genDotFile(type + "value.dot");
		// g.launchViewer(1300, 770);

		// Read discount and tolerance
		o = i.next();
		if (!(o instanceof String)
				|| !((String) o).equalsIgnoreCase("discount")) {
			System.out.println("Missing discount declaration: " + o);
			System.exit(1);
		}
		_bdDiscount = ((BigDecimal) i.next());

		o = i.next();
		if (!(o instanceof String)
				|| !((String) o).equalsIgnoreCase("tolerance")) {
			System.out.println("Missing tolerance declaration: " + o);
			System.exit(1);
		}
		_bdTolerance = ((BigDecimal) i.next());

		// Normalize the reward [0,1] !!!
		// double max = max(_rewardDD);
		// BigDecimal inv_rmax = new BigDecimal(""+((1.0d -
		// _bdDiscount.doubleValue())/max));
		// System.out.println(inv_rmax);
		// System.exit(1);
		// _rewardDD = scalarMultiply(_rewardDD, inv_rmax);
	}

	////////////////////////////////////////////////////////////////////////////
	// /
	// Miscellaneous
	////////////////////////////////////////////////////////////////////////////
	// /

	public String toString() {
		return toString(false, false);
	}

	public String toString(boolean display_reward, boolean display_value) {
		StringBuffer sb = new StringBuffer();
		sb.append("\nMDP Definition:\n===============\n");
		sb.append("Actions (" + _hmName2Action.size() + "):\n");
		// sb.append(_hmName2Action.toString() + "\n\n");
		Iterator actions = _hmName2Action.entrySet().iterator();
		while (actions.hasNext()) {
			Map.Entry me = (Map.Entry) actions.next();
			sb.append("   " + me.getKey() + "\n" /*
												 * + ":\n" + me.getValue() +
												 * "\n\n"
												 */);
			// sb.append("   " + me.getKey() + "\n" + ":\n" + me.getValue() +
			// "\n\n");
		}
		sb.append("\nMDP Definition (cont):\n======================\n");
		sb.append("Vars:        " + _alVars + "\n");
		sb.append("Order:       " + _alOrder + "\n");
		sb.append("ID Map:      " + _tmVar2ID + "\n");
		sb.append("Inverse Map: " + _tmID2Var + "\n");
		sb.append("Discount:    " + _bdDiscount + "\n");
		sb.append("Tolerance:   " + _bdTolerance + "\n");
		sb.append("DD Type:     ");
		String type = null;
		switch (_nDDType) {
		case DD.TYPE_TABLE:
			type = "TABLE";
			break;
		case DD.TYPE_ADD:
			type = "ADD";
			break;
		case DD.TYPE_AADD:
			type = "DAADD";
			break;
		case DD.TYPE_AADDSD:
			type = "AADDSD";
			break;
		case DD.TYPE_AADDDD:
			type = "AADDDD";
			break;
		case DD.TYPE_AADDLD:
			type = "AADDLD";
			break;
		default:
			sb.append("Unknown");
			break;
		}
		sb.append(type + "\n");
		// if (_context.countExactNodes(_rewardDD) < 25/*20*/) {
		// sb.append("Reward - \n" + _context.printNode(_rewardDD) + "\n");
		// }
		// if (_valueDD != null && _context.countExactNodes(_valueDD) <
		// 25/*20*/) {
		// sb.append("Value fun: " + _context.printNode(_valueDD) + "\n");
		// }
		if (display_reward) {
			Graph g = _context.getGraph(_rewardDD, _tmID2Var);
			// g.genDotFile(type + "value.dot");
			g.launchViewer(1300, 770);
		}

		if (display_value) {
			Graph g = _context.getGraph(_valueDD, _tmID2Var);
			// g.genDotFile(type + "value.dot");
			g.launchViewer(1300, 770);
		}

		// if ("AADD".equals(type)) _context.printEnum(_valueDD);

		return sb.toString();
	}

	/**
	 * Compare the last regression of the ADD/AADD representation
	 **/
	public static void CompareLastRegr(MDP_Gen mdp1, MDP_Gen mdp2) {

		// Cycle through all actions and compare CPTs
		System.out.println("Comparing last regression");
		Iterator i1 = mdp1._tmAct2Regr.entrySet().iterator();
		Iterator i2 = mdp2._tmAct2Regr.entrySet().iterator();
		while (i1.hasNext()) {
			Map.Entry me1 = (Map.Entry) i1.next();
			Map.Entry me2 = (Map.Entry) i2.next();
			System.out.println("- Comparing regr "
					+ me1.getKey()
					+ "("
					+ mdp1._context.countExactNodes(me1.getValue())
					+ ") / "
					+ me2.getKey()
					+ "("
					+ mdp2._context.countExactNodes(me2.getValue())
					+ ") md = "
					+ FBR.CompareEnum(mdp1._context, me1.getValue(),
							mdp2._context, me2.getValue()));
			// PrintEnum((ADD)me1.getValue(), (AADD)me2.getValue(),
			// mdp1._tmID2Var, true);
			// if (!((AADD)me2.getValue()).verifyOrder()) {
			// System.out.println("AADD order incorrect!");
			// }
			System.out.println(mdp1._context.printNode(me1.getValue()));
			System.out.println(mdp2._context.printNode(me2.getValue()));
		}
	}

	/**
	 * Compare the ADD and AADD representations
	 **/
	public static void CompareRep(MDP_Gen mdp1, MDP_Gen mdp2) {

		// Compare reward
		System.out.println("Reward md = "
				+ FBR.CompareEnum(mdp1._context, mdp1._rewardDD, mdp2._context,
						mdp2._rewardDD));

		// Cycle through all actions and compare CPTs
		Iterator i1 = mdp1._hmName2Action.values().iterator();
		Iterator i2 = mdp2._hmName2Action.values().iterator();
		while (i1.hasNext()) {
			Action a1 = (Action) i1.next();
			Action a2 = (Action) i2.next();
			System.out.println("Comparing " + a1._sName + "/" + a2._sName);
			Iterator i3 = a1._tmID2DD.entrySet().iterator();
			Iterator i4 = a2._tmID2DD.entrySet().iterator();
			while (i3.hasNext()) {
				Map.Entry me1 = (Map.Entry) i3.next();
				Map.Entry me2 = (Map.Entry) i4.next();
				double diff = FBR.CompareEnum(mdp1._context, me1.getValue(),
						mdp2._context, me2.getValue());
				System.out.println("- Comparing " + me1.getKey() + "/"
						+ me2.getKey() + " md = " + diff);
				/*
				 * if (!((ADD)me1.getValue()).verifyOrder() ||
				 * !((AADD)me2.getValue()).verifyOrder()) {
				 * System.out.println("Order!!!"); System.exit(1); } if (diff >
				 * 0) { System.out.println((ADD)me1.getValue());
				 * System.out.println((AADD)me2.getValue());
				 * PrintEnum((ADD)me1.getValue(), (AADD)me2.getValue(),
				 * mdp1._tmID2Var, true); }
				 */
			}
		}
	}

	public static void ResetTimer() {
		_lTime = System.currentTimeMillis();
	}

	// Get the elapsed time since resetting the timer
	public static long GetElapsedTime() {
		return System.currentTimeMillis() - _lTime;
	}

	public static String MemDisplay() {
		long total = RUNTIME.totalMemory();
		long free = RUNTIME.freeMemory();
		return total - free + ":" + total;
	}

	////////////////////////////////////////////////////////////////////////////
	// /
	// Testing Interface
	////////////////////////////////////////////////////////////////////////////
	// /

//	/**
//	 * Basic testing interface.
//	 **/
	public static void main(String args[]) {
		if (args.length < 6 || args.length > 7) {
			System.out
					.println("\nMust enter MDP-filename, "
							+ "prune-prec (max=1.0), type<none,low,high,min,max,avg,range>"
							+ "\n           iter-Tab iter-ADD iterN [spudd-file]!\n");
			// iterN is the number of iterations for the other DD versions
			System.exit(1);
		}

		// Parse problem filename
		String filename = args[0];
		String spuddfile = null;

		// Parse prune precision and type
		int prune_type = -1;
		double prune_prec = -1d;
		try {
			prune_prec = (new BigDecimal(args[1])).doubleValue();
		} catch (NumberFormatException nfe) {
			System.out.println("\nIllegal precision specification\n");
			System.exit(1);
		}
		if (args[2].equalsIgnoreCase("none")) {
			prune_type = ADD.NO_REPLACE;
		} else if (args[2].equalsIgnoreCase("low")) {
			prune_type = ADD.REPLACE_LOW;
		} else if (args[2].equalsIgnoreCase("high")) {
			prune_type = ADD.REPLACE_HIGH;
		} else if (args[2].equalsIgnoreCase("min")) {
			prune_type = ADD.REPLACE_MIN;
		} else if (args[2].equalsIgnoreCase("max")) {
			prune_type = ADD.REPLACE_MAX;
		} else if (args[2].equalsIgnoreCase("avg")) {
			prune_type = ADD.REPLACE_AVG;
		} else if (args[2].equalsIgnoreCase("range")) {
			prune_type = ADD.REPLACE_RANGE;
		} else {
			System.out.println("\nIllegal prune type");
			System.exit(1);
		}

		// Express pruning in percent of total value, it starts
		// off as percent of 
		System.out.println("\nUSING PRUNE STRENGTH: " + _df.format(prune_prec));

		// Parse iterations
		int iter_tab = -1;
		int iter_add = -1;
		int iterN = -1;
		try {
			iter_tab = Integer.parseInt(args[3]);
			iter_add = Integer.parseInt(args[4]);
			iterN = Integer.parseInt(args[5]);
		} catch (NumberFormatException nfe) {
			System.out.println("\nIllegal iteration value\n");
			System.exit(1);
		}
		if (args.length == 7) {
			spuddfile = args[6];
		}

		// Show args
		System.out.println("\nRunning with args '" + filename + "' "
				+ prune_type + ":" + prune_prec + ", <tab: " + iter_tab
				+ ", add:" + iter_add + ", aadd:" + iterN + ">, "
				+ ((spuddfile == null) ? "no spudd comp" : spuddfile) + "\n");


		final int NDD = 6; //number of DDs 
		final boolean DRAW = false; //Display DDs?
		
		//Generate MDPs qith each DD kind
		MDP_Gen mdp[] = new MDP_Gen[NDD]; 
		String names[] = new String[NDD];
		mdp[0] = new MDP_Gen(filename, DD.TYPE_ADD); names[0] = "ADD";
		mdp[1] = new MDP_Gen(filename, DD.TYPE_TABLE); names[1] = "Table";
		mdp[2] = new MDP_Gen(filename, DD.TYPE_AADD); names[2] = "DAADD";
		mdp[3] = new MDP_Gen(filename, DD.TYPE_AADDSD); names[3] = "AADD-sd";
		mdp[4] = new MDP_Gen(filename, DD.TYPE_AADDDD); names[4] = "AADD-dd";
		mdp[5] = new MDP_Gen(filename, DD.TYPE_AADDLD); names[5] = "AADD-ld";
		
		long iter[] = new long[NDD];
		long time[] = new long[NDD];
		long nodes[] = new long[NDD];
		long cache[] = new long[NDD];
		double max_val[] = new double[NDD];
		
		int itr; //number of iterations for DD
		// Build a new DD MDP from file, display, solve
		for(int i=0;i<NDD;i++){
			ResetTimer();
			itr = iterN; 
			if (i== 0) itr = iter_add;
			if (i== 1) itr = iter_tab;
			iter[i] = mdp[i].solve(itr, prune_type, prune_prec);
			time[i] = GetElapsedTime();
			nodes[i] = mdp[i]._context.countExactNodes(mdp[i]._valueDD);
			cache[i] = mdp[i]._context.getCacheSize();
			max_val[i] = mdp[i]._context.getMaxValue(mdp[i]._valueDD);
			mdp[i].flushCaches();
			System.out.println();
			System.out.println(mdp[i]);
		}

		try {
			PrintWriter os = new PrintWriter(new FileWriter(filename + ".value." + prune_prec));
			os.write("variables ( ");
			for (Object o : ((ADD)mdp[0]._context._context)._alOrder) {
				String var = (String)mdp[0]._tmID2Var.get(o);
				if (!var.endsWith("\'"))
					os.write(var + " ");
			}
			os.write(")\n");
			((ADD)mdp[0]._context._context).dumpToTree(((Integer)mdp[0]._valueDD).intValue(), 
					(Map)mdp[0]._tmID2Var, os, _df, 0);
			os.flush();
			os.close();
		} catch (Exception e) {
			System.out.println("**Error exporting ADD value function to file.");
		}
		
		System.out.println("Final results:");
		System.out.println("--------------\n");
		for(int i=0;i<NDD;i++){
			System.out.println(i+" "+names[i]+": " + iter[i] + " iterations, ("
				+ mdp[i]._nMaxRegrSz + "), " + time[i] + " ms, " + nodes[i]
				+ " nodes, " + cache[i] + " cache, max val: "
				+ DD._df.format(max_val[i]));
	}

		// Compare to SPUDD result if provided
		// Build the SPUDD ADD if appropriate
		double max_error[] = new double[NDD];
		
		String exact_value_filename = filename + ".value.0.0";
		Object exact_value = MDPConverter.buildADD(
				exact_value_filename, mdp[0]._context);

		if (exact_value != null) {
			System.out.println("\n   Read exact value file: " + exact_value_filename);
			for(int i=0;i<NDD;i++){
				max_error[i] = FBR.CompareEnum(mdp[0]._context, exact_value, mdp[i]._context,	mdp[i]._valueDD);
			}			
			
			System.out.print("   Max error (Table,ADD,DAADD,AADD-SD,AADD-DD,AADD-LD): ");
			for(int i=0;i<NDD;i++){
					System.out.print(_df.format(max_error[i]) + ", ");
			}
			System.out.println();
			System.out.println("Max val exact: "
					+ mdp[0]._context.getMaxValue(exact_value) + ", "
					+ mdp[0]._context.countExactNodes(exact_value) + " nodes");
			if (mdp[0]._context.countExactNodes(exact_value) < 20) {
				System.out.println("\n\n------------SPUDD------------");
				System.out.println(exact_value);
				System.out.println("-----------------------------");
			}
		}

		// Compare value functions
//		if (DD.PRUNE_TYPE != DD.REPLACE_RANGE) {
//			for(int i=1;i<NDD;i++){
//				System.out.println("\n   Max diff "+i+" /0 = "
//					+ _df.format(FBR.CompareEnum(mdp[i]._context, mdp[i]._valueDD,
//							mdp[0]._context, mdp[0]._valueDD)));
//			}
//		}
		
		try {
			String[] filename_split = filename.split("[/\\.]");
			String result_filename = "out" + File.separator 
					+ filename_split[filename_split.length - 2] + "_"
					+ _df.format(prune_prec) + ".txt";
			PrintStream result_file = new PrintStream(new FileOutputStream(
					result_filename));
			for(int i=0;i<NDD;i++){
				result_file.println(iter[i] + "\t" + mdp[i]._nMaxRegrSz + "\t" + time[i]
					+ "\t" + nodes[i] + "\t" + cache[i] + "\t"
					+ DD._df.format(max_val[i]) + "\t" + _df.format(max_error[i]));
			}
			result_file.close();
			System.out.println("\n   Wrote results file: " + result_filename);
		} catch (Exception e) {
			System.out.println(e);
		}

		System.out.println();
		
		if (DRAW){
			for(int i=0;i<NDD;i++){
				Graph g = mdp[i]._context.getGraph(mdp[i]._valueDD);
				g.genDotFile("clean"+i+".dot");
				g.launchViewer(1300, 770,0,0,20*i);
			}
		}
	}
}
