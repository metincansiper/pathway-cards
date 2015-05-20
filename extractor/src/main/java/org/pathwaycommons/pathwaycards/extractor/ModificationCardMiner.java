package org.pathwaycommons.pathwaycards.extractor;

import org.biopax.paxtools.io.SimpleIOHandler;
import org.biopax.paxtools.model.BioPAXElement;
import org.biopax.paxtools.model.Model;
import org.biopax.paxtools.model.level3.ModificationFeature;
import org.biopax.paxtools.pattern.Match;
import org.biopax.paxtools.pattern.Pattern;
import org.biopax.paxtools.pattern.PatternBox;
import org.biopax.paxtools.pattern.Searcher;
import org.biopax.paxtools.pattern.miner.AbstractSIFMiner;
import org.biopax.paxtools.pattern.miner.SIFEnum;
import org.biopax.paxtools.pattern.util.Blacklist;

import java.io.*;
import java.net.URL;
import java.util.*;
import java.util.zip.GZIPInputStream;

/**
 * @author Ozgun Babur
 */
public class ModificationCardMiner
{
	private AbstractMiner[] miners = new AbstractMiner[]{
		new CSCO(), new CSCO_ButPart(), new CSCO_ThrContSmMol()};

	public static void main(String[] args) throws IOException
	{
		// A blacklist file is available at http://www.pathwaycommons.org/pc2/downloads/blacklist.txt
		// This is for avoiding ubiquitous small molecules like ATP
		Blacklist black = new Blacklist(new URL(
			"http://www.pathwaycommons.org/pc2/downloads/blacklist.txt").openStream());

		ModificationCardMiner mcm = new ModificationCardMiner();
		mcm.setBlacklist(black);

		SimpleIOHandler io = new SimpleIOHandler();

		// The large model file is available at
		// http://www.pathwaycommons.org/pc2/downloads/Pathway%20Commons.5.Detailed_Process_Data.BIOPAX.owl.gz
		Model model = io.convertFromOWL(new GZIPInputStream(new URL(
			"http://www.pathwaycommons.org/pc2/downloads/Pathway%20Commons.7.Reactome.BIOPAX.owl.gz").
			openStream()));

		mcm.mineAndCollect(model);
		mcm.writeResults("DeltaFeatures.txt");
	}

	abstract class AbstractMiner extends AbstractSIFMiner
	{
		public AbstractMiner()
		{
			super(SIFEnum.CONTROLS_STATE_CHANGE_OF);
		}

		@Override
		public abstract Pattern constructPattern();

		@Override
		public void writeResult(Map<BioPAXElement, List<Match>> matches, OutputStream out) throws IOException
		{
			for (List<Match> matchList : matches.values())
			{
				for (Match m : matchList)
				{
					// find source and target identifiers
					Set<String> s1 = getIdentifiers(m, getSourceLabel());
					Set<String> s2 = getIdentifiers(m, getTargetLabel());

					if (s1.isEmpty() || s2.isEmpty()) continue;

					// collect gained and lost modifications and cellular locations of the target

					Set<String>[] modif = getDeltaModifications(m,
						getInputSimplePELabel(), getInputComplexPELabel(),
						getOutputSimplePELabel(), getOutputComplexPELabel());

					Set<String>[] comps = getDeltaCompartments(m,
						getInputSimplePELabel(), getInputComplexPELabel(),
						getOutputSimplePELabel(), getOutputComplexPELabel());

					// correct for inactive-labelled controllers and negative sign controls
					int sign = sign(m, getControlLabels());
					if (labeledInactive(m, getSourceSimplePELabel(), getSourceComplexPELabel()))
						sign *= -1;

					Set<String> modif0 = modif[sign == -1 ? 1 : 0];
					Set<String> modif1 = modif[sign == -1 ? 0 : 1];
					Set<String> comps0 = comps[sign == -1 ? 1 : 0];
					Set<String> comps1 = comps[sign == -1 ? 0 : 1];

					for (String s1s : s1)
					{
						for (String s2s : s2)
						{
							if (!modif0.isEmpty()) collect(s1s, s2s, modif0, gainMods);
							if (!modif1.isEmpty()) collect(s1s, s2s, modif1, lossMods);
							if (!comps0.isEmpty()) collect(s1s, s2s, comps0, gainComps);
							if (!comps1.isEmpty()) collect(s1s, s2s, comps1, lossComps);

							if (!modif[0].isEmpty() || !modif[1].isEmpty() ||
								!comps[0].isEmpty() || !comps[1].isEmpty())
							{
								// record mediator ids to map these interactions to detailed data

								if (!mediators.containsKey(s1s)) mediators.put(s1s, new HashMap<String, Set<String>>());
								if (!mediators.get(s1s).containsKey(s2s)) mediators.get(s1s).put(s2s, new HashSet<String>());

								List<BioPAXElement> meds = m.get(getMediatorLabels(), getPattern());
								for (BioPAXElement med : meds)
								{
									mediators.get(s1s).get(s2s).add(med.getRDFId());
								}

								// record modifications and cellular locations of the source molecule

								Set<String> mods = getModifications(m, getSourceSimplePELabel(), getSourceComplexPELabel());
								Set<String> locs = getCellularLocations(m, getSourceSimplePELabel(), getSourceComplexPELabel());

								collect(s1s, s2s, mods, sourceMods);
								collect(s1s, s2s, locs, sourceComps);
							}
						}
					}
				}
			}

		}

		private void collect(String s1, String s2, Set<String> modificationFeatures,
			Map<String, Map<String, Set<String>>> map)
		{
			if (!map.containsKey(s1)) map.put(s1, new HashMap<String, Set<String>>());
			if (!map.get(s1).containsKey(s2)) map.get(s1).put(s2, new HashSet<String>());
			map.get(s1).get(s2).addAll(modificationFeatures);
		}


		String getSourceSimplePELabel()
		{
			return "controller simple PE";
		}

		String getSourceComplexPELabel()
		{
			return "controller PE";
		}

		String getInputSimplePELabel()
		{
			return "input simple PE";
		}

		String getOutputSimplePELabel()
		{
			return "output simple PE";
		}

		String getInputComplexPELabel()
		{
			return "input PE";
		}

		String getOutputComplexPELabel()
		{
			return "output PE";
		}

		@Override
		public String getSourceLabel()
		{
			return "controller ER";
		}

		@Override
		public String getTargetLabel()
		{
			return "changed ER";
		}

		@Override
		public String[] getMediatorLabels()
		{
			return new String[]{"Control", "Conversion"};
		}

		public String[] getControlLabels()
		{
			return new String[]{"Control"};
		}

		protected String toString(ModificationFeature mf)
		{
			String term = getModificationTerm(mf);
			if (term != null)
			{
				String loc = getPositionInString(mf);
				return term + loc;
			}
			return null;
		}
	}

	class CSCO extends AbstractMiner
	{
		@Override
		public Pattern constructPattern()
		{
			return PatternGenerator.controlsStateChange();
		}
	}

	class CSCO_ButPart extends AbstractMiner
	{
		@Override
		public Pattern constructPattern()
		{
			return PatternGenerator.controlsStateChangeButIsParticipant();
		}

		@Override
		public String[] getControlLabels()
		{
			return new String[]{};
		}

		@Override
		public String[] getMediatorLabels()
		{
			return new String[]{"Conversion"};
		}
	}

	class CSCO_ThrContSmMol extends AbstractMiner
	{
		@Override
		public Pattern constructPattern()
		{
			return PatternGenerator.controlsStateChangeThroughControllerSmallMolecule(blacklist);
		}

		@Override
		String getSourceSimplePELabel()
		{
			return "upper controller simple PE";
		}

		@Override
		String getSourceComplexPELabel()
		{
			return "upper controller PE";
		}

		@Override
		public String getSourceLabel()
		{
			return "upper controller ER";
		}

		@Override
		public String[] getMediatorLabels()
		{
			return new String[]{"upper Control", "upper Conversion", "Control", "Conversion"};
		}

		@Override
		public String[] getControlLabels()
		{
			return new String[]{"upper Control", "Control"};
		}
	}

	public void setBlacklist(Blacklist blacklist)
	{
		for (AbstractMiner miner : miners)
		{
			miner.setBlacklist(blacklist);
		}
	}

	public void mineAndCollect(Model model)
	{
		for (AbstractMiner miner : miners)
		{
			Map<BioPAXElement, List<Match>> matches = Searcher.search(model, miner.getPattern());

			try { miner.writeResult(matches, null);
			} catch (IOException e){e.printStackTrace();}
		}
	}

	public void writeResults(String filename) throws IOException
	{
		BufferedWriter writer = new BufferedWriter(new FileWriter(filename));
		writer.write("Source\tType\tTarget\tSource-modifs\tSource-locs\tGained-modifs\tLost-modifs\tGained-locs\tLost-locs\tMediators");

		Set<String> s1s = new HashSet<String>(gainMods.keySet());
		s1s.addAll(lossMods.keySet());

		for (String s1 : s1s)
		{
			Set<String> s2s = new HashSet<String>();
			if (gainMods.containsKey(s1)) s2s.addAll(gainMods.get(s1).keySet());
			if (lossMods.containsKey(s1)) s2s.addAll(lossMods.get(s1).keySet());

			for (String s2 : s2s)
			{
				writer.write("\n" + s1 + "\t" + SIFEnum.CONTROLS_STATE_CHANGE_OF.getTag() +
					"\t" + s2);

				writeVal(writer, s1, s2, sourceMods);
				writeVal(writer, s1, s2, sourceComps);
				writeVal(writer, s1, s2, gainMods);
				writeVal(writer, s1, s2, lossMods);
				writeVal(writer, s1, s2, gainComps);
				writeVal(writer, s1, s2, lossComps);
				writer.write("\t" + toString(mediators.get(s1).get(s2)));
			}
		}

		writer.close();
	}

	private void writeVal(BufferedWriter writer, String s1, String s2,
		Map<String, Map<String, Set<String>>> map) throws IOException
	{
		writer.write("\t");
		if (map.containsKey(s1) && map.get(s1).containsKey(s2))
		{
			writer.write(map.get(s1).get(s2).toString());
		}
	}

	private String toString(Set<String> set)
	{
		String s = "";
		for (String s1 : set)
		{
			s += " " + s1;
		}
		return s.substring(1);
	}
}
