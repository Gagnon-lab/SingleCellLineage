# Single-cell GESTALT pipeline

This Docker image processes GESTALT barcode sequencing data. Reads are merged by UMI, aligned to the reference file, and aggregated into plots describing the overall editing, as well as trees describing the relative lineage relationships within your data. 


# Processing data using the GESTALT pipeline

## Before you begin
Things you need to have:
* **A Docker installation**. Follow the documentation here: https://docs.docker.com/engine/installation/

* Reference files. See examples in /app/references/ in the Docker container
  - **reference file (fasta)**: this should be a single entry fasta file, that contains a standard header line that starts with a ">", followed by the sequence of the amplicon. This can be split over many lines if you prefer.
  - **A cutsite file**. This should have the name <reference.fa>.cutSites, where the <reference.fa> is the name of your reference. This describes where the cutsites occur within the amplicon. See the example_data directory for an example version. Positions are relative to the start of the reference.
  - **A primers file**, named <reference.fa>.primers. A file containing the invariant sequences that are expected to be present at both ends of the amplicon. This file is two lines, a primer on each line oriented with the reference. This is used to filter out non-specific PCR products that may be present in your sequencing run. This filtering is optional, and if you don't use this you can just put any sequences in this file.

* **A processing tearsheet**, describing your samples. See the example 'sample_tearsheet.tsv' in the /app/sc_GESTALT/tear_sheet_examples directory.

## Install the Docker container

Download the docker container:
```
docker pull aaronmck/genomics:sc_GESTALT
```

Run and connect to the container, remapping port 80 of the container to port 8080 on your local machine:
```
 docker run -it -p 8080:80 aaronmck/genomics:sc_GESTALT /bin/bash
```
## Run the example script

Now within the container, run the example script:
```
sh /app/sc_GESTALT/tear_sheet_examples/run_crispr_pipeline.sh
```
## Adjust the existing scripts for your data

You can adjust the input files by changing filenames in the tearsheet: app/sc_GESTALT/tear_sheet_examples/basic_example.txt. To mount your data on a local disk to a location within the docker filesystem, use the ```-v``` option:
```
docker run -it -p 8080:80 -v /Users/aaronmck/Desktop/gel_images/:/my_data aaronmck/genomics:sc_GESTALT /bin/bash
```
You can then rerun the example script or create your own:
```
sh /app/sc_GESTALT/tear_sheet_examples/run_crispr_pipeline.sh
```

# Output

The example script creates a number of files in the output directory. This location is specified in the input sample spreadsheet, in the *output.dir* column. See our example spreadsheet used in the script here: /app/sc_GESTALT/tear_sheet_examples/basic_example.txt. In that file the base output location is set to */app/data/*, which should have a sub-directory for the *testrun*. Within the the base output directory there are a couple important output files:

* **dome_4_1X.stats** contains the collapsed reads and the event calls per target site
* **dome_4_1X.umiCounts** information about what UMIs were seen, and if there were enough reads for an individual UMI to call it 'successfully captured'

The pipeline also generates visualization output: plots for the editing pattern over the target, and 'phylogenetic' trees using PHYLIP mix. The default is to put this into the */var/www/html/* directory on the Docker container, but can be set with the --web parameter to the pipeline. If you've remapped your ports above (using -p 8080:80) you should be able to visualize the output by opening localhost:8080 when run locally. 

Within the the base output directory will be a *trees* directory, and a directory for each run. In the example case, our run is called *testrun*, and there will be a directory matching this name. In that, our sample is called *dome_4_1X*. Within that folder, important outputs include: 
