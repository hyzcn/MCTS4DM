package Process;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.PriorityQueue;

import org.apache.lucene.util.OpenBitSet;

import Data.DataType;
import Data.Enum;
import Data.Subgroup;

public class MCTS4DM {

	public static List<Subgroup> mcts4dm(String filename) {
		try {
			Global.initialize();
			long startTime = System.currentTimeMillis();
			List<Subgroup> res = new ArrayList<Subgroup>();

			if (!readParametersFromFile(filename))
				return res;

			getTargetsLength();
			switch (Global.propType) {
			case NUMERIC:
				readFileAttr();
				break;
			case BOOLEAN:
				readFileAttr();
				break;
			case SEQUENCE:
				readFileSeq();
				break;
			case NOMINAL:
				readFileAttrNominal();
				;
				break;
			default:
				break;
			}
			readFileTarget();

			// Global.displayAttributes();
			// Global.displayTargets();
			// Global.displayObjects();

			// Instanciate writters
			Global.repositoryName = "results/" + Global.resFolderName + "/resXP" + System.currentTimeMillis();
			File repository = new File(Global.repositoryName);
			repository.mkdirs();
			Global.bufferResult = new BufferedWriter(new FileWriter(Global.repositoryName + "/result.log"));
			Global.bufferInfo = new BufferedWriter(new FileWriter(Global.repositoryName + "/info.log"));
			Global.bufferSupport = new BufferedWriter(new FileWriter(Global.repositoryName + "/support.log"));
			Global.bufferSupportE11 = new BufferedWriter(new FileWriter(Global.repositoryName + "/supportE11.log"));
			Global.bufferMean = new BufferedWriter(new FileWriter(Global.repositoryName + "/mean.log"));

			// Instanciate the logList that stores the log values
			int size = Global.nbLoops;
			Global.logList = new double[size];

			if (Global.duplicatesExpand == Enum.DuplicatesExpand.AMAF)
				Global.amaf = new HashMap<Subgroup, Subgroup>();

			Global.root = new Subgroup();

			if (Global.mctsType == Enum.MctsType.Unique)
				res = runUniqueMCTS(Global.root);
			else if (Global.mctsType == Enum.MctsType.Independant)
				res = runIndependantMCTS(Global.root);
			else if (Global.mctsType == Enum.MctsType.Subset) {
				res = runSubsetMCTS(Global.root);
			} else
				System.err.println("No mctsType value !");

			long stopTime = System.currentTimeMillis();
			Global.runTime = stopTime - startTime;
			Global.writeInfo();

			Global.bufferResult.close();
			Global.bufferSupport.close();
			Global.bufferSupportE11.close();
			Global.bufferInfo.close();
			Global.bufferMean.close();

			
			
			return res;
		} catch (Exception e) {
			e.printStackTrace();
			return null;
		}
	}

	public static void main(String[] args) {
		if (args.length != 1) {
			System.err.println("Bad parameter");
			return;
		}
		mcts4dm(args[0]);
	}

	/**
	 * Launch a unique MCTS
	 * 
	 * @param root
	 */
	public static List<Subgroup> runSubsetMCTS(Subgroup root) {
		Subgroup[] startingSubgroups = Global.root.startWithTargets();
		List<Subgroup> res = new ArrayList<Subgroup>();
		String[] labels = Global.subsetLabels.split(",");
		if (labels.length > 0) {
			Subgroup aSub = startingSubgroups[Global.targetToId.get(labels[0]).getId()];
			for (int i = 1; i < labels.length && aSub.target != null; i++) {
				aSub.target = aSub.target.expand(Global.targetToId.get(labels[i]).getId(), aSub.description.support);
			}

			res.addAll(launchMCTSFromRoot(aSub));
		} else {
			System.err.println("No subset of labels given in the parameter.conf file");
		}

		return res;

	}

	/**
	 * Launch a unique MCTS
	 * 
	 * @param root
	 */
	public static List<Subgroup> runUniqueMCTS(Subgroup root) {
		Subgroup[] startingSubgroups = Global.root.startWithTargets();
		root.children = new HashMap<Integer, Subgroup>();
		for (int i = 0; i < startingSubgroups.length; i++) {
			root.children.put(i, startingSubgroups[i]);
			startingSubgroups[i].parent = new ArrayList<Subgroup>();
			startingSubgroups[i].parent.add(root);
		}
		return launchMCTSFromRoot(root);
	}

	/**
	 * Launch independant MCTS
	 * 
	 * @param root
	 */
	public static List<Subgroup> runIndependantMCTS(Subgroup root) {
		Subgroup[] startingSubgroups = Global.root.startWithTargets();
		List<Subgroup> res = new ArrayList<Subgroup>();

		// Iterates independently on each initial seeds
		int iFirst, iLast;
		if (Global.extendsWithLabels) {
			iFirst = 0;
			iLast = startingSubgroups.length - 1;
		} else {
			iFirst = 0;
			iLast = 0;
		}
		for (int i = iFirst; i <= iLast; i++) {
			Subgroup aSub = startingSubgroups[i];
			System.out.println(aSub);
			System.out.println("\n[" + (i + 1) + "/" + startingSubgroups.length + "] Exploring :" + aSub + "...");
			res.addAll(launchMCTSFromRoot(aSub));
			aSub.delete();
			// if (!Global.extendsWithLabels)
			// break;
		}
		return res;
	}

	/**
	 * Runs a basic MCTS on the root
	 * 
	 * @param root
	 */
	public static List<Subgroup> launchMCTSFromRoot(Subgroup root) {
		// Runs the iterations
		Global.subRoot = root;
		long startTime = System.currentTimeMillis();
		boolean restart = false;
		try {
			Global.bufferMean.write("#iterations\tmean Measure\n");
			for (int i = 1; i <= Global.nbLoops; i++) {
				if ((i - 2) % 500 == 0) {
					Global.bufferMean.write((i - 1) + "\t" + Global.meanMeasure + "\n");
				}
				if (i % 10000 == 0 && !restart) {
					long stopTime = System.currentTimeMillis();
					long elapsedTime = stopTime - startTime;
					//int depth = root.getDepth();
					// Subgroup max = root.getMaxSubgroup();

					System.out.println(i + " iterations in " + elapsedTime + " ms.");
					// System.out.println("Profondeur : " + depth);
					// System.out.println("Best sg : " + max);
					// System.out.println("max : " + root.getTotValue() + "\t" +
					// Global.subRoot.getMaxRollOut());
					// System.out.println("Evaluations in rollOut : " +
					// Global.nbEvaluationsRoll);
					// Global.displayRunTime();

					startTime = stopTime;
				}
				if (!root.iterateOnce()) {
					i--;
					restart = true;
					if (root.fullTerminated) {
						long stopTime = System.currentTimeMillis();
						long elapsedTime = stopTime - startTime;

						System.out.println("Search space is completly explored in " + i + " iterations in "
								+ elapsedTime + " ms.");
						break;
					}
				} else {
					restart = false;
				}
			}

			// Checks all the redundancy of the result sets
			 //List<Subgroup> resultSet = Global.allSubgroupsList;
			List<Subgroup> resultSet = new ArrayList<Subgroup>();
			while (true) {
				Subgroup aSub = Global.allSubgroups.poll();
				if (aSub == null)
					break;

				// Checks redundancy
				if (Global.maxRedundancy == -1)
					resultSet.add(aSub);
				else if (!aSub.isRedundantWith(resultSet))
					resultSet.add(aSub);

				// Breaks if the beam is full
				if (resultSet.size() >= Global.nbOutput) {
					Global.allSubgroups.clear();
					break;
				}
			}
			Global.allSubgroups.clear();

			Global.resultPatternCount = resultSet.size();
			Global.bufferResult.write("Description\tTarget\tMeasure\tE11\tE10\tE01\n");
			for (Subgroup sg : resultSet) {
				Global.resultLength.add((int) sg.description.getDescriptionSize());
				Global.resultMeasures.add(sg.measure);

				Global.bufferResult.write(sg + "\n");
				Global.bufferSupport.write(sg.writeSupport() + "\n");
				Global.bufferSupportE11.write(sg.writeSupportE11() + "\n");
			}
			Global.bufferResult.flush();
			Global.bufferSupport.flush();
			Global.bufferSupportE11.flush();
			return resultSet;
		} catch (IOException e) {
			e.printStackTrace();
			return null;
		}
	}

	/**
	 * Method that reads the attribute file given as parameter.
	 * 
	 * @param fileName
	 *            : The name of the attribute file.
	 */
	public static void readFileAttr() {
		String fileName = Global.propertiesFile;
		BufferedReader br = null;
		BufferedReader brBis = null;
		String line = "";
		String cvsSplitBy = "\t";

		try {
			boolean firstLine = true;

			// List of priority queues (one for each attribute) containing
			// values of the domain of the attribute
			List<PriorityQueue<Double>> valuesQueue = null;

			// Count the number of objects (lines)
			br = new BufferedReader(new FileReader(fileName));
			int nbLine = 0;
			while ((line = br.readLine()) != null) {
				nbLine++;
			}
			Global.objects = new Data.Object[nbLine - 1];
			br.close();

			br = new BufferedReader(new FileReader(fileName));
			// First loop on the file
			while ((line = br.readLine()) != null) {
				String[] items = line.split(cvsSplitBy);
				int length = items.length;

				// Initializes the array of attributes and the priority queues
				if (firstLine) {
					valuesQueue = new ArrayList<PriorityQueue<Double>>();
					Global.attributes = new Data.Attribute[length];
					Global.nbAttr = length;
					Global.nbChild = 2 * length;
					if (Global.propType == DataType.BOOLEAN)
						Global.nbChild = length;

					for (int i = 0; i < length; i++) {
						if (Global.propType == DataType.NUMERIC)
							Global.attributes[i] = new Data.AttributeNumerical(i, items[i]);
						else if (Global.propType == DataType.BOOLEAN)
							Global.attributes[i] = new Data.AttributeBoolean(i, items[i]);
						else
							System.err.println("Warning : Error in Main.readFileAttr.");
						valuesQueue.add(new PriorityQueue<Double>(Global.objects.length));
					}

					firstLine = false;
					continue;
				}

				// Pushes the values in the priority queues
				for (int i = 0; i < length; i++) {
					Double value = Double.parseDouble(items[i]);
					if (!valuesQueue.get(i).contains(value)) {
						valuesQueue.get(i).add(value);
					}
				}
			}

			// Builds the mapping between values of attributes and indexes
			List<HashMap<Double, Integer>> valueToIndexList = new ArrayList<HashMap<Double, Integer>>();
			for (int i = 0; i < valuesQueue.size(); i++) {
				HashMap<Double, Integer> valueToIndex = new HashMap<Double, Integer>();
				valueToIndexList.add(valueToIndex);
				Data.Attribute theAttr = Global.attributes[i];
				PriorityQueue<Double> pQueue = valuesQueue.get(i);
				double[] tab = new double[pQueue.size()];
				int cpt = 0;
				while (true) {
					Double value = pQueue.poll();
					if (value == null) {
						break;
					}
					tab[cpt] = value;
					valueToIndex.put(value, cpt);
					cpt++;
				}
				if (Global.propType == DataType.NUMERIC)
					((Data.AttributeNumerical) theAttr).setOrderedValues(tab);
			}

			// Second loop : initializes objects
			firstLine = true;
			brBis = new BufferedReader(new FileReader(fileName));
			int numLine = 0;
			int[] suppItems = null;
			if (Global.propType == DataType.BOOLEAN) {
				suppItems = new int[Global.nbChild];
				for (int i = 0 ; i < Global.nbChild ; i++) {
					suppItems[i]=0;
				}
			}
			while ((line = brBis.readLine()) != null) {
				String[] items = line.split(cvsSplitBy);
				int length = items.length;

				if (firstLine) {
					firstLine = false;
					continue;
				}

				Global.objects[numLine] = new Data.Object(numLine, Global.propType);
				for (int i = 0; i < length; i++) {
					Double value = Double.parseDouble(items[i]);

					if (Global.propType == DataType.NUMERIC) {
						int index = valueToIndexList.get(i).get(value);
						Global.objects[numLine].setAttributeValue(i, index);
					} else if (Global.propType == DataType.BOOLEAN && value == 1) {
						Global.objects[numLine].setAttribute(i);
						suppItems[i]++;
					}
				}
				numLine++;
			}
			if (Global.propType == DataType.BOOLEAN) {
				Global.candidatesBoolean = new OpenBitSet(Global.nbChild);
				for (int i = 0 ; i < Global.nbChild ; i++) {
					if (suppItems[i]>= Global.minSup)
						Global.candidatesBoolean.set(i);
					else 
						Global.candidatesBoolean.clear(i);;
				}
			}

		} catch (IOException e) {
			e.printStackTrace();
		} finally {
			if (br != null) {
				try {
					br.close();
				} catch (IOException e) {
					e.printStackTrace();
				}
			}
		}
	}

	/**
	 * Method that reads the attribute file given as parameter.
	 * 
	 * @param fileName
	 *            : The name of the attribute file.
	 */
	public static void readFileAttrNominal() {
		String fileName = Global.propertiesFile;
		BufferedReader br = null;
		BufferedReader brBis = null;
		String line = "";
		String cvsSplitBy = "\t";

		try {
			boolean firstLine = true;

			// List of priority queues (one for each attribute) containing
			// values of the domain of the attribute
			List<HashSet<String>> valuesQueue = null;

			// Count the number of objects (lines)
			br = new BufferedReader(new FileReader(fileName));
			int nbLine = 0;
			while ((line = br.readLine()) != null) {
				nbLine++;
			}
			Global.objects = new Data.Object[nbLine - 1];
			br.close();

			br = new BufferedReader(new FileReader(fileName));
			// First loop on the file
			while ((line = br.readLine()) != null) {
				String[] items = line.split(cvsSplitBy);
				int length = items.length;

				// Initializes the array of attributes and the priority queues
				if (firstLine) {
					valuesQueue = new ArrayList<HashSet<String>>();
					Global.attributes = new Data.Attribute[length];
					Global.nbAttr = length;
					Global.nbChild = 0;
					for (int i = 0; i < length; i++) {
						Global.attributes[i] = new Data.AttributeNominal(i, items[i]);
						valuesQueue.add(new HashSet<String>());
					}

					firstLine = false;
					continue;
				}

				// Pushes the values in the priority queues
				for (int i = 0; i < length; i++) {
					String value = items[i];
					if (!valuesQueue.get(i).contains(value)) {
						valuesQueue.get(i).add(value);
					}
				}
			}

			// Builds the mapping between values of attributes and indexes
			List<HashMap<String, Integer>> valueToIndexList = new ArrayList<HashMap<String, Integer>>();
			int[][] suppItems = new int[Global.attributes.length][];
			for (int i = 0; i < valuesQueue.size(); i++) {
				HashMap<String, Integer> valueToIndex = new HashMap<String, Integer>();
				valueToIndexList.add(valueToIndex);
				Data.Attribute theAttr = Global.attributes[i];
				HashSet<String> pQueue = valuesQueue.get(i);
				String[] tab = new String[pQueue.size()];
				int cpt = 0;
				suppItems[i] = new int[pQueue.size()];
				for (String value : pQueue) {
					tab[cpt] = value;
					suppItems[i][cpt] = 0;
					valueToIndex.put(value, cpt);
					cpt++;
				}
				((Data.AttributeNominal) theAttr).setValues(tab);
				Global.nbChild += tab.length;
			}

			// Second loop : initializes objects
			firstLine = true;
			brBis = new BufferedReader(new FileReader(fileName));
			int numLine = 0;
			while ((line = brBis.readLine()) != null) {
				String[] items = line.split(cvsSplitBy);
				int length = items.length;

				if (firstLine) {
					firstLine = false;
					continue;
				}

				Global.objects[numLine] = new Data.Object(numLine, Global.propType);
				for (int i = 0; i < length; i++) {
					String value = items[i];
					int index = valueToIndexList.get(i).get(value);
					Global.objects[numLine].setAttributeValue(i, index);
					suppItems[i][index]++;
				}
				numLine++;
			}
			
			// Avoid to expand with infrequent items
			int index = 0;
			Global.candidatesNominal = new OpenBitSet(Global.nbChild);
			for (int i = 0 ; i < suppItems.length ; i++) {
				for (int j = 0 ; j < suppItems[i].length; j++) {
					if (suppItems[i][j] >= Global.minSup)
						Global.candidatesNominal.set(index);
					else 
						Global.candidatesNominal.clear(index);
					
					index++;
				}
			}

		} catch (IOException e) {
			e.printStackTrace();
		} finally {
			if (br != null) {
				try {
					br.close();
				} catch (IOException e) {
					e.printStackTrace();
				}
			}
		}
	}

	/**
	 * Method that reads the attribute file of sequences given as parameter.
	 * 
	 * @param fileName
	 *            : The name of the attribute file.
	 */

	public static void readFileSeq() {
		String fileName = Global.propertiesFile;
		BufferedReader br = null;
		String line = "";
		String cvsSplitBy = " ";

		try { // Count the number of objects (lines)
			Global.sequenceIndex = new ArrayList<>();
			br = new BufferedReader(new FileReader(fileName));
			int nbLine = 0;
			int maxIdItems = 0;
			while ((line = br.readLine()) != null) {
				nbLine++;
				String[] items = line.split(cvsSplitBy);
				int length = items.length;
				for (int i = 0; i < length; i++) {
					int value = Integer.parseInt(items[i]);
					if (value > maxIdItems)
						maxIdItems = value;
				}
			}
			Global.objects = new Data.Object[nbLine];
			br.close();
			Global.nbAttr = maxIdItems + 1;
			Global.nbChild = 2 * Global.nbAttr;
			for (int item = 0; item <= maxIdItems; item++) {
				Global.sequenceIndex.add(new ArrayList<List<Integer>>());
				for (int seq = 0; seq < nbLine; seq++) {
					Global.sequenceIndex.get(item).add(new ArrayList<Integer>());
				}
			}

			br = new BufferedReader(new FileReader(fileName));
			int numLine = 0;
			while ((line = br.readLine()) != null) {
				String[] items = line.split(cvsSplitBy);
				int length = items.length;
				int numItemset = 0;

				Global.objects[numLine] = new Data.Object(numLine, DataType.SEQUENCE);
				OpenBitSet bs = new OpenBitSet(Global.nbAttr);
				for (int i = 0; i < length; i++) {
					int value = Integer.parseInt(items[i]);

					if (value == -1) {
						// End of itemset
						Global.objects[numLine].addItemset(bs);
						bs = new OpenBitSet(maxIdItems);
						numItemset++;
						continue;
					}

					if (value >= 0) {
						bs.set(value);
						Global.sequenceIndex.get(value).get(numLine).add(numItemset);
					}
				}
				numLine++;
			}

			// Global.mappingObjSortedToOriginal = new
			// int[Global.objects.length];
			// List<Sequence> list = new ArrayList<Sequence>();
			// for(int idObj = 0 ; idObj < Global.objects.length ; idObj++) {
			// list.add(new
			// Sequence(Global.objects[idObj].descriptionSequence,idObj));
			// }
			// Collections.sort(list, Sequence.sequenceComparator);
			// for (int i = 0 ; i < list.size() ; i ++) {
			// Global.mappingObjSortedToOriginal[i] = list.get(i).id;
			// }

		} catch (IOException e) {
			e.printStackTrace();
		} finally {
			if (br != null) {
				try {
					br.close();
				} catch (IOException e) {
					e.printStackTrace();
				}
			}
		}
	}

	public static void readFileTarget() {
		String fileName = Global.qualitiesFile;
		BufferedReader br = null;
		BufferedReader brBis = null;
		String line = "";
		String cvsSplitBy = "\t";

		try {
			boolean firstLine = true;

			// List of priority queues (one for each target) containing
			// values of the domain of the target
			List<PriorityQueue<Double>> valuesQueue = null;

			br = new BufferedReader(new FileReader(fileName));

			// First loop on the file
			while ((line = br.readLine()) != null) {
				String[] items = line.split(cvsSplitBy);
				int length = items.length;

				// Initializes the array of attributes and the priority queues
				if (firstLine) {
					valuesQueue = new ArrayList<PriorityQueue<Double>>();
					Global.targets = new Data.Attribute[length];
					for (int i = 0; i < length; i++) {
						Global.targets[i] = new Data.AttributeBoolean(i, items[i]);
						Global.targetToId.put(items[i], Global.targets[i]);
						valuesQueue.add(new PriorityQueue<Double>(Global.objects.length));
					}

					firstLine = false;
					continue;
				}

				// Pushes the values in the priority queues
				for (int i = 0; i < length; i++) {
					Double value = Double.parseDouble(items[i]);
					if (!valuesQueue.get(i).contains(value)) {
						valuesQueue.get(i).add(value);
					}
				}
			}

			// Second loop : initializes objects
			firstLine = true;
			brBis = new BufferedReader(new FileReader(fileName));
			int numLine = 0;
			while ((line = brBis.readLine()) != null) {
				String[] items = line.split(cvsSplitBy);
				int length = items.length;

				if (firstLine) {
					firstLine = false;
					continue;
				}

				for (int i = 0; i < length; i++) {
					Double value = Double.parseDouble(items[i]);
					if (value == 1)
						Global.objects[numLine].setTarget(i);
				}
				numLine++;
			}

		} catch (IOException e) {
			e.printStackTrace();
		} finally {
			if (br != null) {
				try {
					br.close();
				} catch (IOException e) {
					e.printStackTrace();
				}
			}
		}
	}

	public static void getTargetsLength() {
		String fileName = Global.qualitiesFile;
		BufferedReader br = null;
		String line = "";
		String cvsSplitBy = "\t";

		try {
			boolean firstLine = true;

			br = new BufferedReader(new FileReader(fileName));

			// First loop on the file
			while ((line = br.readLine()) != null) {
				String[] items = line.split(cvsSplitBy);
				int length = items.length;

				// Initializes the array of attributes and the priority queues
				if (firstLine) {
					Global.targets = new Data.Attribute[length];
					break;
				}

			}

		} catch (IOException e) {
			e.printStackTrace();
		} finally {
			if (br != null) {
				try {
					br.close();
				} catch (IOException e) {
					e.printStackTrace();
				}
			}
		}
	}

	/**
	 * Initialize parameters given by users
	 * 
	 * @param args
	 * @return
	 */
	protected static boolean readParameters(String[] args) {
		if (args.length != 9) {
			System.err.println("Bad parameters : \n" + Global.launchCommand);
			return false;
		}

		Global.qualitiesFile = args[0];
		Global.propertiesFile = args[1];
		Global.nbOutput = Integer.parseInt(args[2]);
		Global.nbLoops = Integer.parseInt(args[3]);
		Global.minSup = Integer.parseInt(args[4]);
		Global.xBeta = Double.parseDouble(args[5]);
		Global.lBeta = Double.parseDouble(args[6]);
		Global.maxRedundancy = Double.parseDouble(args[7]);
		Global.resFolderName = args[8];

		return true;
	}

	/**
	 * Initialize parameters given by users
	 * 
	 * @param args
	 * @return
	 */
	protected static boolean readParametersFromFile(String paramFile) {
		try {
			BufferedReader br = new BufferedReader(new FileReader(paramFile));

			String line;

			while ((line = br.readLine()) != null) {
				if (line.startsWith("#") || !line.contains("="))
					continue;

				line = line.replace(" =", "=");
				line = line.replace("= ", "=");

				String[] temp = line.split("=");
				String paramName = temp[0];
				String paramValue = temp[1];

				paramName = paramName.trim();
				paramValue = paramValue.trim();

				// Dataset parameters
				if (paramName.compareTo("attrFile") == 0) {
					Global.propertiesFile = paramValue;
					continue;
				}

				if (paramName.compareTo("targetFile") == 0) {
					Global.qualitiesFile = paramValue;
					continue;
				}

				if (paramName.compareTo("attrType") == 0) {
					paramValue = paramValue.toLowerCase();

					if (paramValue.compareTo("numeric") == 0)
						Global.propType = DataType.NUMERIC;
					else if (paramValue.compareTo("boolean") == 0)
						Global.propType = DataType.BOOLEAN;
					else if (paramValue.compareTo("sequence") == 0)
						Global.propType = DataType.SEQUENCE;
					else if (paramValue.compareTo("graph") == 0)
						Global.propType = DataType.GRAPH;
					else if (paramValue.compareTo("nominal") == 0)
						Global.propType = DataType.NOMINAL;
					else {
						System.out.println("Bad attribute type. Please check it in the parameter file.");
						br.close();
						return false;
					}

					continue;
				}

				// Result folder parameter
				if (paramName.compareTo("resultFolderName") == 0) {
					Global.resFolderName = paramValue;
					continue;
				}

				// General parameters
				if (paramName.compareTo("minSupp") == 0) {
					Global.minSup = Integer.parseInt(paramValue);
					continue;
				}

				if (paramName.compareTo("nbIter") == 0) {
					Global.nbLoops = Integer.parseInt(paramValue);
					continue;
				}

				if (paramName.compareTo("maxOutput") == 0) {
					Global.nbOutput = Integer.parseInt(paramValue);
					continue;
				}

				// The redundancy strategy
				if (paramName.compareTo("redundancyStrategy") == 0) {
					paramValue = paramValue.toLowerCase();
					if (paramValue.compareTo("jaccardsupportdescription") == 0)
						Global.redundancyStrategy = Enum.Redundancy.JaccardSupportDescription;
					else if (paramValue.compareTo("jaccardsupportdescriptiontarget") == 0)
						Global.redundancyStrategy = Enum.Redundancy.JaccardSupportDescriptionTarget;
					else if (paramValue.compareTo("sumjaccard") == 0)
						Global.redundancyStrategy = Enum.Redundancy.SumJaccard;
					else {
						System.out.println("Bad mctsType value in the parameter file !");
						br.close();
						return false;
					}
					continue;
				}

				// If we care about the equality of the labels for the
				// redundancy test
				if (paramName.compareTo("redundancyIdenticalLabels") == 0) {
					paramValue = paramValue.toLowerCase();
					if (paramValue.compareTo("true") == 0)
						Global.redundancyIdenticalLabels = true;
					else if (paramValue.compareTo("false") == 0)
						Global.redundancyIdenticalLabels = false;
					else {
						System.out.println("Bad mctsType value in the parameter file !");
						br.close();
						return false;
					}
					continue;
				}

				// The maximum threshold redundancy
				if (paramName.compareTo("maxRedundancy") == 0) {
					Global.maxRedundancy = Double.parseDouble(paramValue);
					continue;
				}

				if (paramName.compareTo("maxLength") == 0) {
					int maxLength = Integer.parseInt(paramValue);
					if (maxLength > 0)
						Global.maxLength = maxLength;
					continue;
				}

				// The maximum size of subset of labels
				if (paramName.compareTo("maxLabel") == 0) {
					int maxTarget = Integer.parseInt(paramValue);
					Global.maxLabel = maxTarget;
					continue;
				}

				// The MCTS type
				if (paramName.compareTo("mctsType") == 0) {
					paramValue = paramValue.toLowerCase();
					if (paramValue.compareTo("independant") == 0)
						Global.mctsType = Enum.MctsType.Independant;
					else if (paramValue.compareTo("unique") == 0)
						Global.mctsType = Enum.MctsType.Unique;
					else if (paramValue.compareTo("subset") == 0)
						Global.mctsType = Enum.MctsType.Subset;
					else {
						System.out.println("Bad mctsType value in the parameter file !");
						br.close();
						return false;
					}
					continue;
				}

				// The subset of labels
				if (paramName.compareTo("subset") == 0) {
					Global.subsetLabels = paramValue.replace(" ", "");
					continue;
				}

				// Measures parameters
				if (paramName.compareTo("measure") == 0) {
					Global.extendsWithLabels = true;
					paramValue = paramValue.toLowerCase();
					if (paramValue.compareTo("wracc") == 0)
						Global.measure = Enum.Measure.WRAcc;
					else if (paramValue.compareTo("f1") == 0)
						Global.measure = Enum.Measure.F1;
					else if (paramValue.compareTo("relativef1") == 0)
						Global.measure = Enum.Measure.RelativeF1;
					else if (paramValue.compareTo("weightedrelativef1") == 0)
						Global.measure = Enum.Measure.WeightedRelativeF1;
					else if (paramValue.compareTo("wkl") == 0) {
						Global.measure = Enum.Measure.WKL;
						Global.extendsWithLabels = false;
					} else if (paramValue.compareTo("contingencytable") == 0) {
						Global.measure = Enum.Measure.ContingencyTable;
						Global.extendsWithLabels = false;
					} else if (paramValue.compareTo("fbeta") == 0)
						Global.measure = Enum.Measure.FBeta;
					else if (paramValue.compareTo("relativefbeta") == 0)
						Global.measure = Enum.Measure.RelativeFBeta;
					else if (paramValue.compareTo("weightedrelativefbeta") == 0)
						Global.measure = Enum.Measure.WeightedRelativeFBeta;
					else if (paramValue.compareTo("hammingloss") == 0)
						Global.measure = Enum.Measure.HammingLoss;
					else if (paramValue.compareTo("zerooneloss") == 0)
						Global.measure = Enum.Measure.ZeroOneLoss;
					else if (paramValue.compareTo("racc") == 0)
						Global.measure = Enum.Measure.RAcc;
					else if (paramValue.compareTo("acc") == 0)
						Global.measure = Enum.Measure.Acc;
					else if (paramValue.compareTo("jaccard") == 0)
						Global.measure = Enum.Measure.Jaccard;
					else if (paramValue.compareTo("entropy") == 0)
						Global.measure = Enum.Measure.Entropy;
					else if (paramValue.compareTo("mutualinformation") == 0)
						Global.measure = Enum.Measure.MutualInformation;
					else {
						System.out.println("Bad measure value in the parameter file !");
						br.close();
						return false;
					}

					continue;
				}

				if (paramName.compareTo("xBeta") == 0) {
					Global.xBeta = Double.parseDouble(paramValue);
					continue;
				}

				if (paramName.compareTo("lBeta") == 0) {
					Global.lBeta = Double.parseDouble(paramValue);
					continue;
				}

				// MCTS policies
				// Select policy
				if (paramName.compareTo("UCB") == 0) {
					paramValue = paramValue.toLowerCase();
					if (paramValue.compareTo("ucb1") == 0)
						Global.UCB = Enum.UCB.UCB1;
					else if (paramValue.compareTo("uct") == 0)
						Global.UCB = Enum.UCB.UCT;
					else if (paramValue.compareTo("ucbsp") == 0)
						Global.UCB = Enum.UCB.UCBSP;
					else if (paramValue.compareTo("ucbtuned") == 0)
						Global.UCB = Enum.UCB.UCBTuned;
					else if (paramValue.compareTo("dfsuct") == 0)
						Global.UCB = Enum.UCB.DFSUCT;
					else {
						System.out.println("Bad UCB parameter in the parameter file");
						br.close();
						return false;
					}
					continue;
				}

				// Expand policy
				if (paramName.compareTo("refineExpand") == 0) {
					paramValue = paramValue.toLowerCase();

					if (paramValue.compareTo("direct") == 0)
						Global.refineExpand = Enum.RefineExpand.Direct;
					else if (paramValue.compareTo("generator") == 0)
						Global.refineExpand = Enum.RefineExpand.Generator;
					else if (paramValue.compareTo("tunedgenerator") == 0)
						Global.refineExpand = Enum.RefineExpand.TunedGenerator;
					else if (paramValue.compareTo("prefix") == 0)
						Global.refineExpand = Enum.RefineExpand.Prefix;
					else {
						System.out.println(
								"Bad refinement operator for expand value (refineExpand) in the parameter file !");
						br.close();
						return false;
					}
					continue;
				}

				if (paramName.compareTo("duplicatesExpand") == 0) {
					paramValue = paramValue.toLowerCase();

					if (paramValue.compareTo("none") == 0)
						Global.duplicatesExpand = Enum.DuplicatesExpand.None;
					else if (paramValue.compareTo("amaf") == 0)
						Global.duplicatesExpand = Enum.DuplicatesExpand.AMAF;
					else if (paramValue.compareTo("order") == 0)
						Global.duplicatesExpand = Enum.DuplicatesExpand.Order;
					else {
						System.out
								.println("Bad duplicates for expand value (duplicatesExpand) in the parameter file !");
						br.close();
						return false;
					}
					continue;
				}

				// RollOut policy
				if (paramName.compareTo("pathLength") == 0) {
					Global.pathLength = Integer.parseInt(paramValue);
					continue;
				}

				if (paramName.compareTo("refineRollOut") == 0) {
					paramValue = paramValue.toLowerCase();

					if (paramValue.compareTo("direct") == 0)
						Global.refineRollOut = Enum.RefineRollOut.Direct;
					else if (paramValue.compareTo("large") == 0)
						Global.refineRollOut = Enum.RefineRollOut.Large;
					else {
						System.out.println("Bad refine roll out value (refineRollOut) in the parameter file !");
						br.close();
						return false;
					}
					continue;
				}

				if (paramName.compareTo("jumpingLarge") == 0) {
					Global.jumpingLarge = Integer.parseInt(paramValue);
					continue;
				}

				if (paramName.compareTo("rewardPolicy") == 0) {
					paramValue = paramValue.toLowerCase();

					if (paramValue.compareTo("terminal") == 0)
						Global.rewardPolicy = Enum.RewardPolicy.Terminal;
					else if (paramValue.compareTo("randompick") == 0)
						Global.rewardPolicy = Enum.RewardPolicy.RandomPick;
					else if (paramValue.compareTo("meanpath") == 0)
						Global.rewardPolicy = Enum.RewardPolicy.MeanPath;
					else if (paramValue.compareTo("maxpath") == 0)
						Global.rewardPolicy = Enum.RewardPolicy.MaxPath;
					else if (paramValue.compareTo("meantopk") == 0)
						Global.rewardPolicy = Enum.RewardPolicy.MeanTopK;
					else {
						System.out.println("Bad reward policy value (rewardPolicy) in the parameter file !");
						br.close();
						return false;
					}
					continue;
				}

				if (paramName.compareTo("topKRollOut") == 0) {
					Global.topKRollOut = Integer.parseInt(paramValue);
					if (Global.topKRollOut < 0 && Global.rewardPolicy == Enum.RewardPolicy.MeanTopK) {
						System.out
								.println("Bad K value for topK roll out policy (rewardPolicy) in the parameter file !");
						br.close();
						return false;
					}
					continue;
				}

				// Memory policy
				if (paramName.compareTo("memoryPolicy") == 0) {
					paramValue = paramValue.toLowerCase();

					if (paramValue.compareTo("none") == 0)
						Global.memoryPolicy = Enum.MemoryPolicy.None;
					else if (paramValue.compareTo("allevaluated") == 0)
						Global.memoryPolicy = Enum.MemoryPolicy.AllEvaluated;
					else if (paramValue.compareTo("topk") == 0)
						Global.memoryPolicy = Enum.MemoryPolicy.TopK;
					else {
						System.out.println("Bad memory policy value (memoryPolicy) in the parameter file !");
						br.close();
						return false;
					}
					continue;
				}

				if (paramName.compareTo("topKMemory") == 0) {
					Global.topKMemory = Integer.parseInt(paramValue);
					if (Global.memoryPolicy == Enum.MemoryPolicy.TopK && Global.topKMemory < 0) {
						System.out.println("Bad K value for topK memory policy (topKMemory) in the parameter file !");
						br.close();
						return false;
					}
					continue;
				}

				// Update policy
				if (paramName.compareTo("updatePolicy") == 0) {
					paramValue = paramValue.toLowerCase();

					if (paramValue.compareTo("mean") == 0)
						Global.updatePolicy = Enum.UpdatePolicy.Mean;
					else if (paramValue.compareTo("max") == 0)
						Global.updatePolicy = Enum.UpdatePolicy.Max;
					else if (paramValue.compareTo("meantopk") == 0)
						Global.updatePolicy = Enum.UpdatePolicy.MeanTopK;
					else {
						System.out.println("Bad update policy value (updatePolicy) in the parameter file !");
						br.close();
						return false;
					}
					continue;
				}

				if (paramName.compareTo("topKUpdate") == 0) {
					Global.topKUpdate = Integer.parseInt(paramValue);
					if (Global.topKUpdate < 0 && Global.updatePolicy == Enum.UpdatePolicy.MeanTopK) {
						System.out.println("Bad K value for topK update policy (topKUpdate) in the parameter file !");
						br.close();
						return false;
					}
					continue;
				}
			}

			br.close();

		} catch (

		Exception e) {
			e.printStackTrace();
		}
		return true;
	}

}
