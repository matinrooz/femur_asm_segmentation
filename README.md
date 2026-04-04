# 🦴 Femur Segmentation using Active Shape Modeling (ASM)

## 📌 Overview

This project focuses on **3D femur segmentation from CT images** using **Active Shape Modeling (ASM)** and probabilistic shape fitting techniques.

The goal is to accurately fit a statistical shape model to femur structures using landmark-based alignment and iterative optimization via **Markov Chain Monte Carlo (MCMC)** methods.

The pipeline performs segmentation on both:

* Given dataset (with provided landmarks)
* Test dataset (with manually created landmarks)

---

## 🧠 Methodology

### 1. Statistical Shape Modeling

* A pre-defined **Active Shape Model (ASM)** is used
* Landmarks define the femur geometry
* Shape variations are modeled probabilistically

### 2. Fitting Pipeline

* Input: CT image + landmark points
* Output: Fitted femur shape
* Optimization is done by maximizing posterior probability:

$$
Posterior = Likelihood × Prior × ClosestPoint
$$

Evaluators used:

* `ClosestPointEvaluator`
* `PriorEvaluator`
* `LikelihoodEvaluator`

---

### 3. Markov Chain Optimization (MCMC)

To improve fitting quality, multiple Markov Chains are applied:

* **Chain 1** → Translation + Rotation
* **Chain 2** → + Shape updates
* **Chain 3** → Multi-scale proposals

Each chain runs with **10,000+ iterations** to improve convergence and acceptance rate 

---

## 📊 Results

### Validation Metrics

* Average Distance
* Hausdorff Distance

Results show strong alignment between predicted and ground-truth shapes.

### Key Observations

* Multiple Markov Chains significantly improve segmentation quality
* Poor proposal design leads to unrealistic shapes
* Landmark quality is critical for performance
* Trace plots confirm convergence behavior across chains 

---

## 🧪 Test Set Generalization

* New landmarks were manually created for unseen images
* Same pipeline applied
* Model successfully generalized to test data

---

## 📁 Project Structure

```
.
├── data/                # CT images and datasets
├── datasets/            # Training / validation / test splits
├── fitting_project/     # Core fitting pipeline
├── fitting-results/     # Output results and segmentation
├── plots/               # Visualizations (trace plots, results)
├── src/                 # Source code
├── storedParameters.json
├── build.sbt            # Scala build file
```

---

## ⚙️ Technologies

* Scala (Scalismo framework)
* Statistical Shape Modeling (ASM)
* MCMC / Markov Chains
* 3D Medical Image Processing

---

## 🚀 How to Run

```bash
sbt run
```

Make sure datasets and landmarks are correctly placed in the expected directories.

---

## 📌 Key Contributions

* Designed a **multi-stage MCMC fitting pipeline**
* Improved segmentation accuracy via **proposal tuning**
* Implemented robust evaluation using geometric metrics
* Demonstrated generalization on unseen CT images

---

## 📈 Future Improvements

* Better proposal distributions for faster convergence
* Automated landmark detection
* Deep learning integration (e.g., CNN-based initialization)

---

## 👤 Authors

* Matin Roozbahani
* Hari Narayanan

University of Basel

---

